package com.wellsetups.WellSell.api;

import java.math.BigDecimal;

public record CategoryContribution(
    BigDecimal earned,
    long items,
    BigDecimal permissionMultiplier,
    BigDecimal progressionMultiplier,
    BigDecimal combinedMultiplier) {
  public CategoryContribution {
    if (earned.signum() < 0
        || items < 0
        || permissionMultiplier.signum() <= 0
        || progressionMultiplier.signum() <= 0
        || combinedMultiplier.signum() <= 0) {
      throw new IllegalArgumentException("Invalid category contribution");
    }
  }
}
