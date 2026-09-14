package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.api.Leaderboard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Two fixed global boards and two ranks per online player; no arbitrary placeholder queries. */
public final class RankingCache {
  public record Ranks(long earned, long items) {}

  private static final class Entry {
    private final AtomicLong next = new AtomicLong(Long.MIN_VALUE);
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Ranks ranks;
  }

  private final ConcurrentHashMap<UUID, Entry> players = new ConcurrentHashMap<>();
  private final Function<UUID, CompletableFuture<Ranks>> ranksLoader;
  private final java.util.function.Supplier<
          CompletableFuture<Map<Leaderboard.Mode, List<Leaderboard.Entry>>>>
      topLoader;
  private final BiConsumer<String, Throwable> failure;
  private final LongSupplier clock;
  private final long interval;
  private final Entry global = new Entry();
  private volatile Map<Leaderboard.Mode, List<Leaderboard.Entry>> boards;

  public RankingCache(
      Function<UUID, CompletableFuture<Ranks>> ranksLoader,
      java.util.function.Supplier<CompletableFuture<Map<Leaderboard.Mode, List<Leaderboard.Entry>>>>
          topLoader,
      BiConsumer<String, Throwable> failure,
      LongSupplier clock,
      long interval) {
    this.ranksLoader = ranksLoader;
    this.topLoader = topLoader;
    this.failure = failure;
    this.clock = clock;
    if (interval < 1) {
      throw new IllegalArgumentException("Cache interval must be positive");
    }
    this.interval = interval;
  }

  public void track(UUID player) {
    players.putIfAbsent(player, new Entry());
  }

  public void forget(UUID player) {
    players.remove(player);
  }

  public Ranks ranks(UUID player) {
    Entry entry = players.get(player);
    if (entry == null) {
      return null;
    }
    if (claim(entry)) {
      load(() -> ranksLoader.apply(player))
          .whenComplete(
              (value, error) -> {
                try {
                  if (error != null) {
                    failure.accept("Rank cache refresh failed", error);
                  } else {
                    entry.ranks = value;
                  }
                } finally {
                  entry.loading.set(false);
                }
              });
    }
    return entry.ranks;
  }

  public List<Leaderboard.Entry> top(Leaderboard.Mode mode) {
    if (claim(global)) {
      load(topLoader)
          .whenComplete(
              (value, error) -> {
                try {
                  if (error != null) {
                    failure.accept("Leaderboard cache refresh failed", error);
                  } else {
                    boards = Map.copyOf(value);
                  }
                } finally {
                  global.loading.set(false);
                }
              });
    }
    Map<Leaderboard.Mode, List<Leaderboard.Entry>> snapshot = boards;
    return snapshot == null ? null : snapshot.get(mode);
  }

  private static <T> CompletableFuture<T> load(
      java.util.function.Supplier<CompletableFuture<T>> loader) {
    try {
      return java.util.Objects.requireNonNull(loader.get());
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private boolean claim(Entry entry) {
    synchronized (entry) {
      long now = clock.getAsLong();
      if (now < entry.next.get() || !entry.loading.compareAndSet(false, true)) {
        return false;
      }
      entry.next.set(now + interval);
      return true;
    }
  }
}
