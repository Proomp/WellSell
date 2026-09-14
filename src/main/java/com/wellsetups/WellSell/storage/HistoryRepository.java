package com.wellsetups.WellSell.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface HistoryRepository {
  record HistoryPage(List<SaleRecord> records, long maxPage) {
    public HistoryPage {
      records = List.copyOf(records);
    }
  }

  CompletableFuture<HistoryPage> history(UUID player, Page page);

  CompletableFuture<Optional<UUID>> findPlayer(String name);

  CompletableFuture<PlayerTotals> totals(UUID player);
}
