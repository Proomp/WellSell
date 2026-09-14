package com.wellsetups.WellSell.config;

import com.wellsetups.WellSell.category.CategoryCatalog;
import com.wellsetups.WellSell.category.Progression;

public record ExpansionSettings(
    CategoryCatalog categories,
    Progression progression,
    com.wellsetups.WellSell.gui.ProgressGuiSettings gui,
    com.wellsetups.WellSell.pricing.ContainerPolicy containers,
    com.wellsetups.WellSell.pricing.PricingSettings pricing,
    com.wellsetups.WellSell.pricing.CustomPrices custom,
    com.wellsetups.WellSell.lore.LoreSettings lore) {
  public ExpansionSettings(
      CategoryCatalog categories,
      Progression progression,
      com.wellsetups.WellSell.gui.ProgressGuiSettings gui,
      com.wellsetups.WellSell.pricing.ContainerPolicy containers,
      com.wellsetups.WellSell.pricing.PricingSettings pricing,
      com.wellsetups.WellSell.pricing.CustomPrices custom) {
    this(
        categories,
        progression,
        gui,
        containers,
        pricing,
        custom,
        com.wellsetups.WellSell.lore.LoreSettings.disabled());
  }
}
