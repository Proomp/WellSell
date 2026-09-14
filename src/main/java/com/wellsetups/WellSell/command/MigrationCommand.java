package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.sell.SellService;
import com.wellsetups.WellSell.storage.JdbcHistory;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MigrationCommand {
  private final Supplier<Settings> settings;
  private final JdbcHistory storage;
  private final SellService selling;
  private final PlatformExecutor platform;
  private final Messages messages;
  private final boolean enabled;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean copied = new AtomicBoolean();

  public MigrationCommand(
      Supplier<Settings> settings,
      JdbcHistory storage,
      SellService selling,
      PlatformExecutor platform,
      Messages messages,
      boolean enabled) {
    this.settings = settings;
    this.storage = storage;
    this.selling = selling;
    this.platform = platform;
    this.messages = messages;
    this.enabled = enabled;
  }

  public boolean permitted(CommandSender sender) {
    return settings.get().permissions().has(sender, PermissionId.ADMIN)
        && settings.get().permissions().has(sender, PermissionId.MIGRATE);
  }

  public void execute(CommandSender sender, String[] args) {
    if (!permitted(sender)) {
      messages.send(sender, "no-permission");
      return;
    }
    if (!enabled) {
      messages.send(sender, "migration-disabled");
      return;
    }
    if (args.length != 2
        || !(args[1].equalsIgnoreCase("mysql") || args[1].equalsIgnoreCase("mariadb"))) {
      messages.send(sender, "migration-usage");
      return;
    }
    if (!running.compareAndSet(false, true)) {
      messages.send(sender, "migration-busy");
      return;
    }
    if (!storage.ready() || !selling.pauseForMigration()) {
      running.set(false);
      messages.send(sender, "migration-busy");
      return;
    }
    messages.send(sender, "migration-start");
    storage
        .migrateToMaria()
        .whenComplete(
            (counts, failure) -> {
              if (failure == null) {
                copied.set(true);
              } else {
                if (!copied.get()) {
                  selling.resumeAfterMigration();
                }
                storage.logFailure(
                    "Database migration failed; source and existing target rows were preserved",
                    failure);
              }
              running.set(false);
              Runnable reply =
                  () ->
                      messages.send(
                          sender,
                          failure == null ? "migration-success" : "migration-failed",
                          Map.of(
                              "amount",
                              counts == null
                                  ? "0"
                                  : Long.toString(counts.getOrDefault("ws_sales", 0L))));
              if (sender instanceof Player player) {
                platform.player(player, reply, () -> {});
              } else {
                platform.global(reply);
              }
            });
  }
}
