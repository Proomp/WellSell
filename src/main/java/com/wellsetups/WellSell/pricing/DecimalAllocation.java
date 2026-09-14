package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic cumulative allocation: each rounded share conserves the exact batch total. */
public final class DecimalAllocation {
  private DecimalAllocation() {}

  public static <K> Map<K, BigDecimal> split(
      BigDecimal total, Map<K, BigDecimal> weights, int scale) {
    if (total.signum() < 0 || total.scale() > scale) {
      throw new IllegalArgumentException("Invalid allocation total");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal weight : weights.values()) {
      if (weight.signum() <= 0) {
        throw new IllegalArgumentException("Allocation weights must be positive");
      }
      sum = sum.add(weight);
    }
    if (weights.isEmpty() && total.signum() > 0) {
      throw new IllegalArgumentException("Missing allocation weights");
    }
    Map<K, BigDecimal> result = new LinkedHashMap<>();
    BigDecimal accumulated = BigDecimal.ZERO;
    BigDecimal allocated = BigDecimal.ZERO;
    for (var entry : weights.entrySet()) {
      accumulated = accumulated.add(entry.getValue());
      BigDecimal target = total.multiply(accumulated).divide(sum, scale, RoundingMode.DOWN);
      result.put(entry.getKey(), target.subtract(allocated));
      allocated = target;
    }
    return result;
  }
}
