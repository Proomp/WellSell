package com.wellsetups.WellSell.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.api.CategoryContribution;
import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.Leaderboard;
import com.wellsetups.WellSell.api.SaleSummary;
import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatisticsTest {
  @TempDir Path directory;
  private static final UUID FIRST = new UUID(0, 1);
  private static final UUID SECOND = new UUID(0, 2);

  @Test
  void categorySummaryAndTotalsRecoverExactlyOnce() throws Exception {
    SaleRecord sale = sale(FIRST, "12.34", 4, "ores");
    RecoveryJournal journal = new RecoveryJournal(directory.resolve("audit"));
    journal.write(sale, "SUCCESS");
    try (JdbcHistory db = open()) {
      await(db.finish(sale, "SUCCESS"));
      var stats = await(db.statistics(FIRST));
      assertEquals(1, stats.sales());
      assertEquals(
          new CategoryStatistics(new BigDecimal("12.34"), 4), stats.categories().get("ores"));
      assertEquals(
          sale.summary(), await(db.history(FIRST, new Page(1, 5))).records().get(0).summary());
    }
    try (JdbcHistory db = open()) {
      assertEquals(1, await(db.statistics(FIRST)).sales());
    }
  }

  @Test
  void cancelledAndRejectedSalesNeverAdvanceCategories() throws Exception {
    try (JdbcHistory db = open()) {
      for (String state : new String[] {"CANCELLED", "REJECTED", "CHANGED"}) {
        SaleRecord sale = sale(FIRST, "20", 5, "farming");
        await(db.prepare(sale));
        await(db.finish(sale, state));
      }
      assertEquals(0, await(db.statistics(FIRST)).sales());
      assertTrue(await(db.statistics(FIRST)).categories().isEmpty());
    }
  }

  @Test
  void leaderboardsSortDecimalsNumericallyAndPaginateStableTies() throws Exception {
    try (JdbcHistory db = open()) {
      await(db.finish(sale(SECOND, "10.000001", 3, "ores"), "SUCCESS"));
      await(db.finish(sale(FIRST, "10.000001", 2, "ores"), "SUCCESS"));
      await(db.finish(sale(new UUID(0, 3), "9.999999", 7, "farming"), "SUCCESS"));
      var page = await(db.leaderboard(Leaderboard.Mode.TOTAL_EARNED, null, new Page(1, 1)));
      assertEquals(FIRST, page.entries().get(0).player());
      assertEquals(3, page.maxPage());
      assertEquals(
          SECOND,
          await(db.leaderboard(Leaderboard.Mode.TOTAL_EARNED, null, new Page(2, 1)))
              .entries()
              .get(0)
              .player());
      assertEquals(2L, await(db.rank(SECOND, Leaderboard.Mode.TOTAL_EARNED, null)));
      assertEquals(
          new UUID(0, 3),
          await(db.leaderboard(Leaderboard.Mode.TOTAL_ITEMS_SOLD, null, new Page(1, 5)))
              .entries()
              .get(0)
              .player());
      assertEquals(
          2,
          await(db.leaderboard(Leaderboard.Mode.TOTAL_EARNED, "ores", new Page(1, 5)))
              .entries()
              .size());
      assertEquals(
          SECOND,
          await(db.leaderboard(Leaderboard.Mode.TOTAL_ITEMS_SOLD, "ores", new Page(1, 5)))
              .entries()
              .get(0)
              .player());
      assertEquals(1L, await(db.rank(SECOND, Leaderboard.Mode.TOTAL_ITEMS_SOLD, "ores")));
    }
  }

  @Test
  void emptyAndUnknownRanksAreBoundedAndInputIsValidated() throws Exception {
    try (JdbcHistory db = open()) {
      assertEquals(
          1, await(db.leaderboard(Leaderboard.Mode.TOTAL_EARNED, null, new Page(1, 10))).maxPage());
      assertEquals(0L, await(db.rank(FIRST, Leaderboard.Mode.TOTAL_EARNED, null)));
      assertThrows(
          Exception.class,
          () -> await(db.leaderboard(Leaderboard.Mode.TOTAL_EARNED, "' OR 1=1", new Page(1, 10))));
    }
  }

  @Test
  void versionOneUpgradePreservesHistoryAndBackfillsWithoutInventingCategories() throws Exception {
    try (JdbcHistory db = open()) {
      await(db.finish(sale(FIRST, "42.50", 7, null), "SUCCESS"));
    }
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.db"));
        var sql = connection.createStatement()) {
      sql.executeUpdate("DROP TABLE ws_sale_details");
      sql.executeUpdate("DROP TABLE ws_categories");
      sql.executeUpdate("DROP TABLE ws_stats");
      sql.executeUpdate("UPDATE ws_schema SET version=1");
    }
    try (JdbcHistory db = open()) {
      var stats = await(db.statistics(FIRST));
      assertEquals(new BigDecimal("42.50"), stats.earned());
      assertEquals(7, stats.items());
      assertEquals(1, stats.sales());
      assertEquals("Seller1", stats.name());
      assertTrue(stats.categories().isEmpty());
      assertEquals(
          SaleSummary.empty(), await(db.history(FIRST, new Page(1, 5))).records().get(0).summary());
    }
    try (JdbcHistory db = open()) {
      assertEquals(1, await(db.statistics(FIRST)).sales());
    }
  }

  @Test
  void failedCategoryWriteRollsBackHistoryAndGlobalAggregates() throws Exception {
    SaleRecord sale = sale(FIRST, "1.00", 1, "ores");
    try (JdbcHistory db = open()) {
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.db"));
          var sql = connection.createStatement()) {
        sql.executeUpdate(
            "CREATE TRIGGER fail_category BEFORE INSERT ON ws_categories BEGIN SELECT RAISE(ABORT,'test failure'); END");
      }
      assertThrows(Exception.class, () -> await(db.finish(sale, "SUCCESS")));
      assertEquals(0, await(db.statistics(FIRST)).sales());
      assertEquals(0, await(db.history(FIRST, new Page(1, 5))).records().size());
      assertEquals(PlayerTotals.empty(), await(db.totals(FIRST)));
      try (var connection =
              DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("history.db"));
          var sql = connection.createStatement()) {
        sql.executeUpdate("DROP TRIGGER fail_category");
      }
    }
    try (JdbcHistory db = open()) {
      assertEquals(1, await(db.statistics(FIRST)).sales());
      assertEquals(1, await(db.statistics(FIRST)).categories().get("ores").items());
    }
  }

  private JdbcHistory open() throws Exception {
    var db =
        new JdbcHistory(
            new ConfigTree(Map.of("type", "SQLITE", "sqlite-file", "history.db")),
            directory,
            Logger.getLogger("WellSell-statistics-test"),
            ignored -> {});
    await(db.initialize());
    return db;
  }

  private static SaleRecord sale(UUID player, String money, int items, String category) {
    BigDecimal value = new BigDecimal(money);
    SaleSummary summary =
        category == null
            ? SaleSummary.empty()
            : new SaleSummary(
                "CONFIG",
                Map.of(
                    category,
                    new CategoryContribution(
                        value, items, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)),
                0);
    return new SaleRecord(
        UUID.randomUUID(),
        player,
        "Seller" + player.getLeastSignificantBits(),
        Instant.now(),
        items,
        value,
        BigDecimal.ONE,
        "HAND",
        "",
        summary);
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }
}
