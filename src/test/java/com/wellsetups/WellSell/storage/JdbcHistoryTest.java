package com.wellsetups.WellSell.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcHistoryTest {
  @TempDir Path directory;
  private final UUID player = UUID.randomUUID();

  @Test
  void successfulSalesAreIdempotentAndMoneyRemainsExact() throws Exception {
    try (JdbcHistory repository = open(new HashSet<>())) {
      SaleRecord sale = sale("0.10", 1);
      await(repository.prepare(sale));
      await(repository.finish(sale, "SUCCESS"));
      await(repository.finish(sale, "SUCCESS"));
      await(repository.finish(sale("0.20", 2), "SUCCESS"));
      PlayerTotals totals = await(repository.totals(player));
      assertEquals(new BigDecimal("0.30"), totals.earned());
      assertEquals(3, totals.items());
      assertEquals(2, await(repository.history(player, new Page(1, 50))).records().size());
      assertFalse(Files.exists(directory.resolve("audit/" + sale.id() + ".properties")));
    }
  }

  @Test
  void preparedAndUncertainSalesNeverAppearInHistoryOrGetReplayed() throws Exception {
    SaleRecord prepared = sale("100", 1);
    try (JdbcHistory first = open(new HashSet<>())) {
      await(first.prepare(prepared));
      assertEquals(0, await(first.history(player, new Page(1, 8))).records().size());
    }
    Set<UUID> quarantined = new HashSet<>();
    try (JdbcHistory recovered = open(quarantined)) {
      assertTrue(quarantined.contains(player));
      assertEquals(PlayerTotals.empty(), await(recovered.totals(player)));
      assertTrue(Files.exists(directory.resolve("audit/" + prepared.id() + ".properties")));
    }
  }

  @Test
  void confirmedJournalRecordsRecoverHistoryExactlyOnce() throws Exception {
    SaleRecord sale = sale("12.34", 4);
    try (JdbcHistory first = open(new HashSet<>())) {
      await(first.prepare(sale));
    }
    RecoveryJournal journal = new RecoveryJournal(directory.resolve("audit"));
    journal.write(sale, "SUCCESS");
    try (JdbcHistory recovered = open(new HashSet<>())) {
      assertEquals(new BigDecimal("12.34"), await(recovered.totals(player)).earned());
    }
    journal.write(sale, "SUCCESS");
    try (JdbcHistory recoveredAgain = open(new HashSet<>())) {
      assertEquals(new BigDecimal("12.34"), await(recoveredAgain.totals(player)).earned());
      assertEquals(4, await(recoveredAgain.totals(player)).items());
    }
  }

  @Test
  void rejectedSaleDoesNotAffectTotalsAndRemovesIntent() throws Exception {
    SaleRecord sale = sale("10", 1);
    try (JdbcHistory repository = open(new HashSet<>())) {
      await(repository.prepare(sale));
      await(repository.finish(sale, "REJECTED"));
      assertEquals(PlayerTotals.empty(), await(repository.totals(player)));
      assertFalse(Files.exists(directory.resolve("audit/" + sale.id() + ".properties")));
    }
  }

  @Test
  void historyIsBoundedPaginatedAndNameLookupIsCaseInsensitive() throws Exception {
    try (JdbcHistory repository = open(new HashSet<>())) {
      for (int index = 1; index <= 5; index++) {
        await(repository.finish(sale(Integer.toString(index), index), "SUCCESS"));
      }
      HistoryRepository.HistoryPage first = await(repository.history(player, new Page(1, 2)));
      HistoryRepository.HistoryPage second = await(repository.history(player, new Page(2, 2)));
      assertEquals(2, first.records().size());
      assertEquals(3, first.maxPage());
      assertEquals(2, second.records().size());
      assertFalse(first.records().get(0).id().equals(second.records().get(0).id()));
      assertEquals(1, await(repository.history(player, new Page(3, 2))).records().size());
      assertTrue(await(repository.history(player, new Page(4, 2))).records().isEmpty());
      assertEquals(player, await(repository.findPlayer("tEsTeR")).orElseThrow());
      assertTrue(await(repository.findPlayer("' OR 1=1 --")).isEmpty());
    }
  }

  @Test
  void olderRecoveredSaleDoesNotReplaceLastSaleValue() throws Exception {
    SaleRecord older = sale("1.00", 1);
    SaleRecord newer =
        new SaleRecord(
            UUID.randomUUID(),
            player,
            "Tester",
            older.time().plusSeconds(10),
            1,
            new BigDecimal("9.00"),
            BigDecimal.ONE,
            "HAND",
            "");
    try (JdbcHistory repository = open(new HashSet<>())) {
      await(repository.finish(newer, "SUCCESS"));
      await(repository.finish(older, "SUCCESS"));
      assertEquals(new BigDecimal("9.00"), await(repository.totals(player)).lastSale());
      assertEquals(new BigDecimal("10.00"), await(repository.totals(player)).earned());
    }
  }

  @Test
  void journalRoundTripsRecoveryPayloadAndForcesStateBeforeReturning() throws Exception {
    RecoveryJournal journal = new RecoveryJournal(directory.resolve("audit"));
    SaleRecord sale = sale("1.25", 3);
    journal.write(sale, "PREPARED");
    RecoveryJournal.Entry entry =
        journal.read(directory.resolve("audit/" + sale.id() + ".properties"));
    assertEquals(sale, entry.sale());
    assertEquals("PREPARED", entry.state());
  }

  @Test
  void closingRepositoryRejectsNewWorkWithoutBlocking() throws Exception {
    JdbcHistory repository = open(new HashSet<>());
    repository.close();
    assertTrue(repository.totals(player).isCompletedExceptionally());
    assertFalse(repository.ready());
  }

  private JdbcHistory open(Set<UUID> quarantined) throws Exception {
    ConfigTree config = new ConfigTree(Map.of("type", "SQLITE", "sqlite-file", "history.db"));
    JdbcHistory repository =
        new JdbcHistory(config, directory, Logger.getLogger("WellSell-test"), quarantined::add);
    await(repository.initialize());
    return repository;
  }

  private SaleRecord sale(String money, int amount) {
    return new SaleRecord(
        UUID.randomUUID(),
        player,
        "Tester",
        Instant.now(),
        amount,
        new BigDecimal(money),
        new BigDecimal("1.25"),
        "HAND",
        "exact-base64-recovery-payload");
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }
}
