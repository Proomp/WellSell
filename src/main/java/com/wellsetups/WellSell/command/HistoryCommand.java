package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.storage.HistoryRepository;
import com.wellsetups.WellSell.storage.Page;
import com.wellsetups.WellSell.storage.SaleRecord;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HistoryCommand {
  private record Request(String target, UUID ownId, boolean own, Page page, String senderKey) {}

  private final HistoryRepository history;
  private final Supplier<Settings> settings;
  private final PlatformExecutor platform;
  private final Messages messages;
  private final Logger logger;
  private final Set<String> pending = ConcurrentHashMap.newKeySet();

  public HistoryCommand(
      HistoryRepository history,
      Supplier<Settings> settings,
      PlatformExecutor platform,
      Messages messages,
      Logger logger) {
    this.history = history;
    this.settings = settings;
    this.platform = platform;
    this.messages = messages;
    this.logger = logger;
  }

  public void execute(CommandSender sender, String[] args) {
    if (args.length > 2) {
      messages.send(sender, "invalid-command");
      return;
    }
    Request request = request(sender, args);
    if (request == null) {
      return;
    }
    if (!pending.add(request.senderKey())) {
      messages.send(sender, "busy");
      return;
    }
    CompletableFuture<Optional<UUID>> resolved =
        request.own()
            ? CompletableFuture.completedFuture(Optional.ofNullable(request.ownId()))
            : resolve(request.target());
    resolved
        .thenCompose(
            id ->
                id.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : history.history(id.get(), request.page()))
        .whenComplete((result, failure) -> deliver(sender, request, result, failure));
  }

  private Request request(CommandSender sender, String[] args) {
    String ownName = sender instanceof Player ? sender.getName() : "";
    UUID ownId = sender instanceof Player player ? player.getUniqueId() : null;
    boolean firstIsPage = args.length > 0 && args[0].matches("[+-]?[0-9]+") && args.length == 1;
    String target = args.length == 0 || firstIsPage ? ownName : args[0];
    if (target.isEmpty()) {
      messages.send(sender, "player-only");
      return null;
    }
    boolean own =
        target.equalsIgnoreCase(ownName)
            || ownId != null && target.equalsIgnoreCase(ownId.toString());
    if (!own && !settings.get().permissions().has(sender, PermissionId.HISTORY_OTHERS)) {
      messages.send(sender, "no-permission");
      return null;
    }
    Page page;
    try {
      String number = pageArgument(args, firstIsPage);
      page =
          new Page(
              Integer.parseInt(number),
              settings.get().general().integer("history.page-size", 1, 50));
    } catch (IllegalArgumentException failure) {
      messages.send(sender, "invalid-page", Map.of("max_page", Integer.toString(Page.MAX_PAGE)));
      return null;
    }
    String senderKey = ownId == null ? "console:" + sender.getName() : ownId.toString();
    return new Request(target, ownId, own, page, senderKey);
  }

  private static String pageArgument(String[] args, boolean firstIsPage) {
    if (args.length == 2) {
      return args[1];
    }
    return firstIsPage ? args[0] : "1";
  }

  private void deliver(
      CommandSender sender,
      Request request,
      HistoryRepository.HistoryPage result,
      Throwable failure) {
    Runnable reply =
        () -> {
          try {
            reply(sender, request, result, failure);
          } finally {
            pending.remove(request.senderKey());
          }
        };
    if (sender instanceof Player player) {
      platform.player(player, reply, () -> pending.remove(request.senderKey()));
    } else {
      pending.remove(request.senderKey());
      platform.global(reply);
    }
  }

  private void reply(
      CommandSender sender,
      Request request,
      HistoryRepository.HistoryPage result,
      Throwable failure) {
    if (!settings.get().permissions().has(sender, PermissionId.HISTORY)
        || !request.own()
            && !settings.get().permissions().has(sender, PermissionId.HISTORY_OTHERS)) {
      return;
    }
    if (failure != null) {
      messages.send(sender, "history-error");
      logger.log(Level.WARNING, "History query failed", failure);
    } else if (result == null) {
      messages.send(sender, "player-not-found");
    } else {
      render(sender, request.target(), request.page(), result);
    }
  }

  private CompletableFuture<Optional<UUID>> resolve(String name) {
    try {
      return CompletableFuture.completedFuture(Optional.of(UUID.fromString(name)));
    } catch (IllegalArgumentException notUuid) {
      if (!name.matches("[a-zA-Z0-9_]{1,32}")) {
        return CompletableFuture.completedFuture(Optional.empty());
      }
      return history.findPlayer(name);
    }
  }

  private void render(
      CommandSender sender, String target, Page page, HistoryRepository.HistoryPage result) {
    Settings snapshot = settings.get();
    Map<String, String> navigation =
        Map.of(
            "target",
            target,
            "page",
            Integer.toString(page.number()),
            "max_page",
            Long.toString(result.maxPage()),
            "previous",
            Integer.toString(Math.max(1, page.number() - 1)),
            "next",
            Long.toString(Math.min(result.maxPage(), page.number() + 1L)));
    if (page.number() > result.maxPage()) {
      messages.send(sender, "invalid-page", navigation);
      return;
    }
    messages.send(sender, "history-header", navigation);
    for (SaleRecord sale : result.records()) {
      Map<String, String> row = new HashMap<>(navigation);
      row.put("date", snapshot.dateFormat().format(sale.time()));
      row.put("amount", Integer.toString(sale.amount()));
      row.put("money", snapshot.formatMoney(sale.money()));
      row.put("multiplier", sale.multiplier().stripTrailingZeros().toPlainString());
      row.put("source", snapshot.locale().text("source-" + sale.source().toLowerCase(Locale.ROOT)));
      row.put("transaction", sale.id().toString());
      row.put("provider", sale.summary().provider());
      row.put("containers", Integer.toString(sale.summary().containers()));
      row.put(
          "categories",
          String.join(", ", new java.util.TreeSet<>(sale.summary().categories().keySet())));
      messages.send(sender, "history-row", row);
      if (snapshot.general().flag("history.category-details")) {
        details(sender, sale, snapshot);
      }
    }
    if (result.records().isEmpty()) {
      messages.send(sender, "history-empty");
    } else {
      messages.send(sender, "history-navigation", navigation);
    }
  }

  private void details(CommandSender sender, SaleRecord sale, Settings snapshot) {
    new java.util.TreeMap<>(sale.summary().categories())
        .forEach(
            (id, part) ->
                messages.send(
                    sender,
                    "history-category",
                    Map.of(
                        "category",
                        id,
                        "money",
                        snapshot.formatMoney(part.earned()),
                        "amount",
                        Long.toString(part.items()),
                        "permission_multiplier",
                        part.permissionMultiplier().stripTrailingZeros().toPlainString(),
                        "progression_multiplier",
                        part.progressionMultiplier().stripTrailingZeros().toPlainString(),
                        "multiplier",
                        part.combinedMultiplier().stripTrailingZeros().toPlainString())));
  }
}
