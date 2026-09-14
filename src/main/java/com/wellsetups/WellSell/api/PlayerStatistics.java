package com.wellsetups.WellSell.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record PlayerStatistics(
    UUID player,
    String name,
    BigDecimal earned,
    long items,
    long sales,
    BigDecimal lastSale,
    Map<String, CategoryStatistics> categories) {
  public PlayerStatistics {
    categories = Map.copyOf(categories);
  }

  public static PlayerStatistics empty(UUID player) {
    return new PlayerStatistics(player, "", BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, Map.of());
  }
}
