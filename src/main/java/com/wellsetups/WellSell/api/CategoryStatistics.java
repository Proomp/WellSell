package com.wellsetups.WellSell.api;

import java.math.BigDecimal;

public record CategoryStatistics(BigDecimal earned, long items) {
  public CategoryStatistics {
    if (earned.signum() < 0 || items < 0) {
      throw new IllegalArgumentException("Negative category statistics");
    }
  }

  public static CategoryStatistics empty() {
    return new CategoryStatistics(BigDecimal.ZERO, 0);
  }
}
