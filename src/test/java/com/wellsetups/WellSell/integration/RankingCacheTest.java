package com.wellsetups.WellSell.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wellsetups.WellSell.api.Leaderboard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RankingCacheTest {
  @Test
  void manyRequestsShareOneAsyncReadAndRetainSnapshotWhileRefreshing() {
    AtomicLong time = new AtomicLong();
    AtomicInteger reads = new AtomicInteger();
    var pending = new CompletableFuture<Map<Leaderboard.Mode, List<Leaderboard.Entry>>>();
    var cache =
        new RankingCache(
            id -> CompletableFuture.completedFuture(new RankingCache.Ranks(2, 3)),
            () -> {
              reads.incrementAndGet();
              return pending;
            },
            (message, failure) -> {},
            time::get,
            30);
    for (int index = 0; index < 200; index++) {
      assertNull(cache.top(Leaderboard.Mode.TOTAL_EARNED));
    }
    assertEquals(1, reads.get());
    var row = new Leaderboard.Entry(1, UUID.randomUUID(), "First", new BigDecimal("42.12"), 5);
    pending.complete(
        Map.of(
            Leaderboard.Mode.TOTAL_EARNED,
            List.of(row),
            Leaderboard.Mode.TOTAL_ITEMS_SOLD,
            List.of(row)));
    assertEquals(row, cache.top(Leaderboard.Mode.TOTAL_EARNED).get(0));
    assertEquals(1, reads.get());
    time.set(31);
    assertEquals(row, cache.top(Leaderboard.Mode.TOTAL_EARNED).get(0));
    assertEquals(2, reads.get());
  }

  @Test
  void onlyTrackedPlayersCanRequestRanksAndQuitDiscardsLateResults() {
    AtomicInteger reads = new AtomicInteger();
    var pending = new CompletableFuture<RankingCache.Ranks>();
    var cache =
        new RankingCache(
            id -> {
              reads.incrementAndGet();
              return pending;
            },
            () -> CompletableFuture.completedFuture(Map.of()),
            (message, failure) -> {},
            () -> 0,
            30);
    UUID player = UUID.randomUUID();
    assertNull(cache.ranks(player));
    assertEquals(0, reads.get());
    cache.track(player);
    assertNull(cache.ranks(player));
    assertNull(cache.ranks(player));
    assertEquals(1, reads.get());
    cache.forget(player);
    pending.complete(new RankingCache.Ranks(1, 1));
    assertNull(cache.ranks(player));
  }
}
