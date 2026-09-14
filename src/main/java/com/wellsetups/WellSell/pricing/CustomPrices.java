package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.NamespacedKey;

public record CustomPrices(List<NamespacedKey> keys, Map<String, BigDecimal> prices) {
  public CustomPrices {
    keys = List.copyOf(keys);
    prices = Map.copyOf(prices);
  }

  public static CustomPrices load(ConfigTree config, Consumer<String> warning) {
    List<NamespacedKey> keys =
        config.strings("pdc-keys").stream()
            .map(
                value -> {
                  NamespacedKey key = NamespacedKey.fromString(value);
                  if (key == null) {
                    throw new IllegalArgumentException("Invalid trusted PDC key " + value);
                  }
                  return key;
                })
            .distinct()
            .toList();
    if (keys.size() > 16) {
      throw new IllegalArgumentException("At most 16 trusted PDC keys are supported");
    }
    Map<String, BigDecimal> prices = new LinkedHashMap<>();
    config
        .section("prices")
        .forEach(
            (id, raw) -> {
              try {
                BigDecimal price = new BigDecimal(String.valueOf(raw));
                if (!valid(id)
                    || price.signum() <= 0
                    || price.scale() > 12
                    || price.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0) {
                  throw new IllegalArgumentException("Invalid custom item identity or price");
                }
                prices.put(id, price);
              } catch (IllegalArgumentException failure) {
                warning.accept("Ignored custom price " + id + ": " + failure.getMessage());
              }
            });
    return new CustomPrices(keys, prices);
  }

  public static boolean valid(String id) {
    return id != null
        && id.length() <= 128
        && !id.startsWith("minecraft:")
        && id.matches("[a-z0-9_.-]+:[a-z0-9_./:-]+");
  }

  public Optional<BigDecimal> price(String id) {
    return Optional.ofNullable(prices.get(id));
  }
}
