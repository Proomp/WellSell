package com.wellsetups.WellSell.storage;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact positive decimal ordering shared by SQLite and MariaDB, without floating-point SUM. */
final class DecimalOrder {
  private DecimalOrder() {}

  static String key(BigDecimal value) {
    if (value.signum() < 0 || value.toPlainString().length() > 64) {
      throw new IllegalArgumentException("Negative ranking value");
    }
    String digits = value.setScale(6, RoundingMode.UNNECESSARY).unscaledValue().toString();
    if (digits.length() > 76) {
      throw new IllegalArgumentException("Ranking value exceeds storage bounds");
    }
    return "0".repeat(76 - digits.length()) + digits;
  }
}
