package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.api.PlayerStatistics;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Online-player cache; stale in-flight reads cannot replace a newer refresh or a new session. */
public final class PlayerStatisticsCache {
  private record Entry(CompletableFuture<PlayerStatistics> future, long retryAfter) {}

  private final java.util.function.LongSupplier clock;
  private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
  private final Function<UUID, CompletableFuture<PlayerStatistics>> loader;
  private final BiConsumer<String, Throwable> failure;

  public PlayerStatisticsCache(
      Function<UUID, CompletableFuture<PlayerStatistics>> loader,
      BiConsumer<String, Throwable> failure) {
    this(loader, failure, System::nanoTime);
  }

  PlayerStatisticsCache(
      Function<UUID, CompletableFuture<PlayerStatistics>> loader,
      BiConsumer<String, Throwable> failure,
      java.util.function.LongSupplier clock) {
    this.clock = clock;
    this.loader = loader;
    this.failure = failure;
  }

  public void track(UUID player) {
    entries.computeIfAbsent(player, this::load);
  }

  public void refresh(UUID player) {
    entries.computeIfPresent(player, (id, ignored) -> load(id));
  }

  private Entry load(UUID player) {
    CompletableFuture<PlayerStatistics> future;
    try {
      future = java.util.Objects.requireNonNull(loader.apply(player));
    } catch (RuntimeException problem) {
      future = CompletableFuture.failedFuture(problem);
    }
    future.whenComplete(
        (value, error) -> {
          if (error != null) {
            failure.accept("Cannot load category statistics for " + player, error);
          }
        });
    return new Entry(future, clock.getAsLong() + 5_000_000_000L);
  }

  public PlayerStatistics peek(UUID player) {
    Entry entry = entries.get(player);
    return entry == null || entry.future().isCompletedExceptionally()
        ? null
        : entry.future().getNow(null);
  }

  public PlayerStatistics require(UUID player) {
    track(player);
    entries.computeIfPresent(
        player,
        (id, entry) ->
            entry.future().isCompletedExceptionally() && clock.getAsLong() >= entry.retryAfter()
                ? load(id)
                : entry);
    return peek(player);
  }

  public void forget(UUID player) {
    entries.remove(player);
  }
}
