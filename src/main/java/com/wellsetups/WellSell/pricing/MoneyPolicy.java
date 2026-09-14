package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record MoneyPolicy(int scale, RoundingMode rounding, BigDecimal maximum) {
  public static final BigDecimal HARD_MAXIMUM = new BigDecimal("1000000000");

  public MoneyPolicy {
    Objects.requireNonNull(rounding);
    Objects.requireNonNull(maximum);
    if (scale < 0
        || scale > 6
        || maximum.signum() <= 0
        || maximum.compareTo(HARD_MAXIMUM) > 0
        || rounding == RoundingMode.UNNECESSARY) {
      throw new IllegalArgumentException("Invalid money scale, rounding mode or maximum-sale");
    }
  }

  public BigDecimal round(BigDecimal subtotal, BigDecimal multiplier) {
    if (subtotal.signum() < 0
        || multiplier.signum() <= 0
        || multiplier.compareTo(new BigDecimal("100")) > 0) {
      throw new IllegalArgumentException("Invalid subtotal or multiplier");
    }
    BigDecimal result = subtotal.multiply(multiplier).setScale(scale, rounding);
    if (result.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("Sale exceeds maximum-sale");
    }
    return result;
  }
}
