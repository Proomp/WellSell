package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.api.Leaderboard;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.storage.JdbcHistory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LeaderboardCommand {
  private final JdbcHistory storage;
  private final Supplier<Settings> settings;
  private final Messages messages;
  private final PlatformExecutor platform;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();

  public LeaderboardCommand(
      JdbcHistory storage,
      Supplier<Settings> settings,
      Messages messages,
      PlatformExecutor platform) {
    this.storage = storage;
    this.settings = settings;
    this.messages = messages;
    this.platform = platform;
  }

  public void execute(CommandSender sender, String[] args) {
    Settings snapshot = settings.get();
    if (!snapshot.general().flag("leaderboard.enabled")) {
      messages.send(sender, "disabled");
      return;
    }
    LeaderboardRequest request;
    try {
      request =
          LeaderboardRequest.parse(
              args,
              snapshot.expansion().categories().categories().keySet(),
              snapshot.general().integer("leaderboard.page-size", 1, 50),
              Leaderboard.Mode.valueOf(snapshot.general().text("leaderboard.default-mode")));
    } catch (IllegalArgumentException failure) {
      messages.send(sender, "top-usage");
      return;
    }
    String key =
        sender instanceof Player player
            ? player.getUniqueId().toString()
            : "console:" + sender.getName();
    if (!pending.add(key)) {
      messages.send(sender, "busy");
      return;
    }
    storage
        .leaderboard(request.mode(), request.category(), request.page())
        .whenComplete(
            (result, failure) -> {
              Runnable reply =
                  () -> {
                    try {
                      deliver(sender, request, result, failure);
                    } finally {
                      pending.remove(key);
                    }
                  };
              if (sender instanceof Player player) {
                platform.player(player, reply, () -> pending.remove(key));
              } else {
                pending.remove(key);
                platform.global(reply);
              }
            });
  }

  private void deliver(
      CommandSender sender, LeaderboardRequest request, Leaderboard result, Throwable failure) {
    if (!settings.get().permissions().has(sender, PermissionId.TOP)) {
      return;
    }
    if (failure != null) {
      storage.logFailure("Leaderboard query failed", failure);
      messages.send(sender, "history-error");
      return;
    }
    Map<String, String> values = new HashMap<>();
    values.put(
        "category",
        request.category() == null
            ? settings.get().locale().text("top-global")
            : request.category());
    values.put("page", Integer.toString(request.page().number()));
    values.put("max_page", Long.toString(result.maxPage()));
    values.put(
        "mode",
        settings
            .get()
            .locale()
            .text(request.mode() == Leaderboard.Mode.TOTAL_EARNED ? "top-earned" : "top-items"));
    if (request.page().number() > result.maxPage()) {
      messages.send(sender, "invalid-page", values);
      return;
    }
    messages.send(sender, "top-header", values);
    for (Leaderboard.Entry entry : result.entries()) {
      values.put("rank", Long.toString(entry.rank()));
      values.put("target", entry.name());
      values.put("money", settings.get().formatMoney(entry.earned()));
      values.put("amount", Long.toString(entry.items()));
      messages.send(
          sender,
          request.mode() == Leaderboard.Mode.TOTAL_EARNED ? "top-row-earned" : "top-row-items",
          values);
    }
    messages.send(sender, result.entries().isEmpty() ? "top-empty" : "top-footer", values);
  }

  public List<String> suggestions() {
    List<String> values =
        new ArrayList<>(settings.get().expansion().categories().categories().keySet());
    values.addAll(List.of("1", "2", "3", "--earned", "--items"));
    return values;
  }
}
