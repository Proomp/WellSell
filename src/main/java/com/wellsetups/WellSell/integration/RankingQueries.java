package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.api.Leaderboard;
import com.wellsetups.WellSell.storage.JdbcHistory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RankingQueries {
  private RankingQueries() {}

  public static RankingCache cache(JdbcHistory storage, int positions, int seconds) {
    if (positions < 1 || positions > 100 || seconds < 1 || seconds > 300) {
      throw new IllegalArgumentException("Invalid ranking cache bounds");
    }
    return new RankingCache(
        id ->
            storage
                .rank(id, Leaderboard.Mode.TOTAL_EARNED, null)
                .thenCombine(
                    storage.rank(id, Leaderboard.Mode.TOTAL_ITEMS_SOLD, null),
                    RankingCache.Ranks::new),
        () ->
            board(storage, Leaderboard.Mode.TOTAL_EARNED, positions)
                .thenCombine(
                    board(storage, Leaderboard.Mode.TOTAL_ITEMS_SOLD, positions),
                    (earned, items) ->
                        Map.of(
                            Leaderboard.Mode.TOTAL_EARNED,
                            earned,
                            Leaderboard.Mode.TOTAL_ITEMS_SOLD,
                            items)),
        storage::logFailure,
        System::nanoTime,
        seconds * 1_000_000_000L);
  }

  private static CompletableFuture<List<Leaderboard.Entry>> board(
      JdbcHistory storage, Leaderboard.Mode mode, int positions) {
    return storage.top(mode, positions);
  }
}
