package com.wellsetups.WellSell.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record Leaderboard(List<Entry> entries, long maxPage) {
  public enum Mode {
    TOTAL_EARNED,
    TOTAL_ITEMS_SOLD
  }

  public record Entry(long rank, UUID player, String name, BigDecimal earned, long items) {}

  public Leaderboard {
    entries = List.copyOf(entries);
  }
}
