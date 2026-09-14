package com.wellsetups.WellSell.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wellsetups.WellSell.api.PlayerStatistics;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class PlayerStatisticsCacheTest {
  @Test
  void staleReadCannotReplaceRefreshedProgressionAndQuitRemovesData() {
    var reads = new ArrayList<CompletableFuture<PlayerStatistics>>();
    var cache =
        new PlayerStatisticsCache(
            id -> {
              var future = new CompletableFuture<PlayerStatistics>();
              reads.add(future);
              return future;
            },
            (message, failure) -> {});
    UUID id = UUID.randomUUID();
    cache.track(id);
    assertNull(cache.peek(id));
    cache.track(id);
    assertEquals(1, reads.size());
    cache.refresh(id);
    reads.get(0).complete(PlayerStatistics.empty(id));
    assertNull(cache.peek(id));
    reads.get(1).complete(PlayerStatistics.empty(id));
    assertEquals(PlayerStatistics.empty(id), cache.peek(id));
    cache.forget(id);
    assertNull(cache.peek(id));
    cache.refresh(id);
    assertEquals(2, reads.size());
  }

  @Test
  void unavailableDatabaseRetriesAreThrottledAndRecoverWithoutReconnectSpam() {
    var now = new java.util.concurrent.atomic.AtomicLong();
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    UUID id = UUID.randomUUID();
    var cache =
        new PlayerStatisticsCache(
            player -> {
              if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("offline");
              }
              return CompletableFuture.completedFuture(PlayerStatistics.empty(player));
            },
            (message, failure) -> {},
            now::get);
    for (int request = 0; request < 100; request++) {
      assertNull(cache.require(id));
    }
    assertEquals(1, attempts.get());
    now.set(5_000_000_000L);
    assertEquals(PlayerStatistics.empty(id), cache.require(id));
    assertEquals(2, attempts.get());
  }
}
