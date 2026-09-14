package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlaceholderCache implements Listener {
  private record Entry(Player player, BigDecimal multiplier) {}

  private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
  private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();
  private final com.wellsetups.WellSell.storage.PlayerStatisticsCache statistics;
  private final RankingCache rankings;
  private final int positions;
  private final PlatformExecutor platform;
  private final Supplier<Settings> settings;
  private final Function<Player, BigDecimal> multiplier;

  public PlaceholderCache(
      RankingCache rankings,
      int positions,
      PlatformExecutor platform,
      Supplier<Settings> settings,
      Function<Player, BigDecimal> multiplier,
      com.wellsetups.WellSell.storage.PlayerStatisticsCache statistics) {
    this.statistics = statistics;
    this.rankings = rankings;
    this.positions = positions;
    this.platform = platform;
    this.settings = settings;
    this.multiplier = multiplier;
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    add(event.getPlayer());
  }

  public void add(Player player) {
    statistics.track(player.getUniqueId());
    rankings.track(player.getUniqueId());
    entries.put(player.getUniqueId(), new Entry(player, multiplier.apply(player)));
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    statistics.forget(event.getPlayer().getUniqueId());
    rankings.forget(event.getPlayer().getUniqueId());
    entries.remove(event.getPlayer().getUniqueId());
  }

  public String value(UUID player, String key) {
    if (key.startsWith("top_")) {
      return top(key);
    }
    Entry entry = player == null ? null : entries.get(player);
    if (entry == null) {
      return "";
    }
    if (key.equals("multiplier")) {
      refreshMultiplier(player, entry.player());
      return entry.multiplier().stripTrailingZeros().toPlainString();
    }
    if (key.equals("rank_earned") || key.equals("rank_items")) {
      RankingCache.Ranks ranks = rankings.ranks(player);
      return ranks == null
          ? loading()
          : Long.toString(key.equals("rank_earned") ? ranks.earned() : ranks.items());
    }
    return StatisticPlaceholders.value(
        key,
        statistics.peek(player),
        settings.get().expansion().categories(),
        settings.get().expansion().progression(),
        loading());
  }

  private String loading() {
    return settings.get().locale().text("placeholder-loading");
  }

  private String top(String key) {
    String[] parts = key.split("_");
    if (parts.length != 4
        || !Set.of("earned", "items").contains(parts[1])
        || !Set.of("name", "value").contains(parts[3])) {
      return null;
    }
    int position;
    try {
      position = Integer.parseInt(parts[2]);
    } catch (NumberFormatException invalid) {
      return null;
    }
    if (position < 1 || position > positions) {
      return null;
    }
    var mode =
        parts[1].equals("earned")
            ? com.wellsetups.WellSell.api.Leaderboard.Mode.TOTAL_EARNED
            : com.wellsetups.WellSell.api.Leaderboard.Mode.TOTAL_ITEMS_SOLD;
    var board = rankings.top(mode);
    if (board == null) {
      return loading();
    }
    if (position > board.size()) {
      return "";
    }
    var entry = board.get(position - 1);
    if (parts[3].equals("name")) {
      return entry.name();
    }
    return mode == com.wellsetups.WellSell.api.Leaderboard.Mode.TOTAL_EARNED
        ? entry.earned().toPlainString()
        : Long.toString(entry.items());
  }

  private void refreshMultiplier(UUID id, Player player) {
    if (!refreshing.add(id)) {
      return;
    }
    platform.player(
        player,
        () -> {
          try {
            BigDecimal value = multiplier.apply(player);
            entries.computeIfPresent(
                id, (ignored, previous) -> new Entry(previous.player(), value));
          } finally {
            refreshing.remove(id);
          }
        },
        () -> refreshing.remove(id));
  }
}
