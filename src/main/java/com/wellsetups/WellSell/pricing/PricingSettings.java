package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.HashSet;
import java.util.List;

public record PricingSettings(
    boolean enabled, Strategy strategy, List<Provider> providers, boolean multiplier) {
  public enum Strategy {
    FIRST_AVAILABLE,
    HIGHEST,
    LOWEST
  }

  public enum Provider {
    CUSTOM_ITEMS,
    ECONOMYSHOPGUI,
    SHOPGUIPLUS,
    WELLSELL_PRICES
  }

  public PricingSettings {
    providers = List.copyOf(providers);
  }

  public static PricingSettings load(ConfigTree config) {
    List<Provider> providers = config.strings("providers").stream().map(Provider::valueOf).toList();
    if (providers.isEmpty()
        || providers.size() > 4
        || new HashSet<>(providers).size() != providers.size()) {
      throw new IllegalArgumentException("Choose 1..4 unique price providers");
    }
    return new PricingSettings(
        config.flag("enabled"),
        Strategy.valueOf(config.text("strategy")),
        providers,
        config.flag("apply-wellsell-multiplier"));
  }
}
