package com.wellsetups.WellSell.storage;

import java.math.BigDecimal;

public record PlayerTotals(BigDecimal earned, long items, BigDecimal lastSale) {
  public static PlayerTotals empty() {
    return new PlayerTotals(BigDecimal.ZERO, 0, BigDecimal.ZERO);
  }
}
