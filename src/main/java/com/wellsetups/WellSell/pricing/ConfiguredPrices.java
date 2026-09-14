package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class ConfiguredPrices implements PriceProvider {
  private final Map<String, BigDecimal> prices;

  public ConfiguredPrices(
      Map<String, Object> entries, Predicate<String> materialExists, Consumer<String> warning) {
    Map<String, BigDecimal> validated = new HashMap<>();
    entries.forEach(
        (key, raw) -> {
          String material = key.toUpperCase(Locale.ROOT);
          try {
            if (!materialExists.test(material) || !key.equals(material)) {
              throw new IllegalArgumentException("Use an existing uppercase item material name");
            }
            BigDecimal price = new BigDecimal(String.valueOf(raw));
            if (price.signum() <= 0
                || price.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0
                || price.scale() > 12) {
              throw new IllegalArgumentException(
                  "Price must be positive, <= 1000000000 and have <= 12 decimals");
            }
            validated.put(material, price);
          } catch (IllegalArgumentException exception) {
            warning.accept("Ignored prices." + key + " = " + raw + ": " + exception.getMessage());
          }
        });
    prices = Map.copyOf(validated);
  }

  @Override
  public Optional<BigDecimal> unitPrice(String material) {
    return Optional.ofNullable(prices.get(material));
  }

  public int size() {
    return prices.size();
  }
}
