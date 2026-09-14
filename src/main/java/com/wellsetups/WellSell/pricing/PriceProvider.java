package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.Optional;

/** Material identifiers are Bukkit enum names. Implementations must be thread safe. */
public interface PriceProvider {
  Optional<BigDecimal> unitPrice(String material);
}
