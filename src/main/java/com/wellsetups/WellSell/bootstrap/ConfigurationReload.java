package com.wellsetups.WellSell.bootstrap;

import com.wellsetups.WellSell.command.WellSellCommand;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.config.SettingsLoader;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;

final class ConfigurationReload {
  private final Plugin plugin;
  private final SettingsLoader loader;
  private final AtomicReference<Settings> settings;
  private final PlatformExecutor platform;
  private final Messages messages;
  private final Set<Permission> permissions;
  private final List<WellSellCommand> commands;
  private final java.util.function.Consumer<Player> invalidate;
  private final Settings startup;
  private final AtomicBoolean loading = new AtomicBoolean();

  ConfigurationReload(
      Plugin plugin,
      SettingsLoader loader,
      AtomicReference<Settings> settings,
      PlatformExecutor platform,
      Messages messages,
      Set<Permission> permissions,
      List<WellSellCommand> commands,
      java.util.function.Consumer<Player> invalidate) {
    this.plugin = plugin;
    this.loader = loader;
    this.settings = settings;
    this.platform = platform;
    this.messages = messages;
    this.permissions = permissions;
    this.commands = commands;
    this.invalidate = invalidate;
    startup = settings.get();
  }

  void reload(CommandSender sender) {
    if (!loading.compareAndSet(false, true)) {
      messages.send(sender, "reload-busy");
      return;
    }
    platform
        .async(
            () -> {
              try {
                return loader.load();
              } catch (IOException failure) {
                throw new UncheckedIOException(failure);
              }
            })
        .whenComplete(
            (replacement, failure) ->
                platform.global(
                    () -> {
                      try {
                        if (failure != null) {
                          plugin
                              .getLogger()
                              .log(
                                  Level.WARNING,
                                  "Configuration reload rejected; keeping previous snapshot",
                                  failure);
                          reply(sender, "reload-failed");
                          return;
                        }
                        replacement
                            .permissions()
                            .register(plugin.getServer().getPluginManager(), permissions);
                        settings.set(replacement);
                        commands.forEach(WellSellCommand::refreshPermission);
                        for (Player player : plugin.getServer().getOnlinePlayers()) {
                          platform.player(
                              player,
                              () -> {
                                invalidate.accept(player);
                                player.updateCommands();
                              },
                              () -> {});
                        }
                        reply(sender, "reloaded");
                        if (!replacement.commands().equals(startup.commands())
                            || !replacement.storage().values().equals(startup.storage().values())
                            || !replacement
                                .integrations()
                                .values()
                                .equals(startup.integrations().values())) {
                          reply(sender, "restart-required");
                        }
                      } finally {
                        loading.set(false);
                      }
                    }));
  }

  private void reply(CommandSender sender, String key) {
    if (sender instanceof Player player) {
      platform.player(player, () -> messages.send(player, key), () -> {});
    } else {
      messages.send(sender, key);
    }
  }
}
