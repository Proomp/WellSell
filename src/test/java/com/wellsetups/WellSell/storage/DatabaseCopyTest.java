package com.wellsetups.WellSell.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wellsetups.WellSell.api.CategoryContribution;
import com.wellsetups.WellSell.api.SaleSummary;
import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseCopyTest {
  @TempDir Path directory;

  @Test
  void fullCopyPreservesHistoryAggregatesCategoriesAndResumesWithoutDuplication() throws Exception {
    seed();
    try (Connection source = connect("source.db");
        Connection target = connect("target.db")) {
      var counts = DatabaseCopy.copy(source, target, false);
      assertEquals(2L, counts.get("ws_sales"));
      assertEquals(2L, counts.get("ws_categories"));
      assertEquals(counts, DatabaseCopy.copy(source, target, false));
      assertEquals("3.30", scalar(target, "SELECT money FROM ws_totals"));
      assertEquals("2", scalar(target, "SELECT sales FROM ws_stats"));
      assertEquals("3.30", scalar(source, "SELECT money FROM ws_totals"));
      assertEquals("2", scalar(source, "SELECT COUNT(*) FROM ws_sales"));
    }
  }

  @Test
  void interruptedCopyResumesMatchingRowsAndVerifiesTheRemainingTables() throws Exception {
    seed();
    try (Connection source = connect("source.db");
        Connection target = connect("target.db")) {
      new DatabaseSchema(false).migrate(target);
      try (var sql = target.createStatement()) {
        sql.executeUpdate(
            "CREATE TRIGGER fail_copy BEFORE INSERT ON ws_categories BEGIN SELECT RAISE(ABORT,'test interruption'); END");
      }
      assertThrows(java.sql.SQLException.class, () -> DatabaseCopy.copy(source, target, false));
      assertEquals("2", scalar(target, "SELECT COUNT(*) FROM ws_sales"));
      try (var sql = target.createStatement()) {
        sql.executeUpdate("DROP TRIGGER fail_copy");
      }
      target.commit();
      assertEquals(2L, DatabaseCopy.copy(source, target, false).get("ws_sales"));
      assertEquals("2", scalar(target, "SELECT COUNT(*) FROM ws_categories"));
    }
  }

  @Test
  void conflictingTargetDataIsNeverOverwrittenAndSourceRemainsUnchanged() throws Exception {
    seed();
    try (Connection source = connect("source.db");
        Connection target = connect("target.db")) {
      DatabaseCopy.copy(source, target, false);
      try (var sql = target.createStatement()) {
        sql.executeUpdate("UPDATE ws_totals SET money='999'");
      }
      target.commit();
      assertThrows(java.sql.SQLException.class, () -> DatabaseCopy.copy(source, target, false));
      assertEquals("999", scalar(target, "SELECT money FROM ws_totals"));
      assertEquals("3.30", scalar(source, "SELECT money FROM ws_totals"));
    }
  }

  private void seed() throws Exception {
    var config = new ConfigTree(Map.of("type", "SQLITE", "sqlite-file", "source.db"));
    try (var storage =
        new JdbcHistory(config, directory, Logger.getLogger("copy-test"), ignored -> {})) {
      storage.initialize().get(10, TimeUnit.SECONDS);
      UUID player = UUID.randomUUID();
      for (int index = 1; index <= 2; index++) {
        BigDecimal money = new BigDecimal("1.10").multiply(BigDecimal.valueOf(index));
        var summary =
            new SaleSummary(
                "CONFIG",
                Map.of(
                    index == 1 ? "ores" : "farming",
                    new CategoryContribution(
                        money, index, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)),
                0);
        var sale =
            new SaleRecord(
                UUID.randomUUID(),
                player,
                "Tester",
                Instant.now(),
                index,
                money,
                BigDecimal.ONE,
                "HAND",
                "",
                summary);
        storage.finish(sale, "SUCCESS").get(10, TimeUnit.SECONDS);
      }
    }
  }

  private Connection connect(String name) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(name));
  }

  private static String scalar(Connection connection, String query) throws Exception {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery(query)) {
      if (!result.next()) {
        throw new AssertionError("Expected query result");
      }
      return result.getString(1);
    }
  }
}
