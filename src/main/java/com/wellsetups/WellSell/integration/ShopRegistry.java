package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.pricing.PriceSource;
import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.pricing.ShopPrices;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.plugin.Plugin;

public final class ShopRegistry {
  private final Plugin plugin;
  private final boolean folia;
  private final boolean allowFolia;
  private final Map<PriceSource, ShopPrices> adapters = new EnumMap<>(PriceSource.class);

  public ShopRegistry(Plugin plugin, boolean folia, boolean allowFolia) {
    this.plugin = plugin;
    this.folia = folia;
    this.allowFolia = allowFolia;
  }

  public synchronized ShopPrices require(PriceSource source) {
    if (folia && !allowFolia) {
      throw new PriceUnavailableException("External shop pricing requires prices.allow-on-folia");
    }
    if (!enabled(source)) {
      throw new PriceUnavailableException("Selected shop is not enabled: " + source);
    }
    try {
      return adapters.computeIfAbsent(
          source,
          selected ->
              switch (selected) {
                case ECONOMYSHOPGUI -> new EconomyShopPrices();
                case SHOPGUIPLUS -> new ShopGuiPrices();
                case CONFIG -> throw new IllegalArgumentException("CONFIG is not an external shop");
              });
    } catch (LinkageError failure) {
      throw new PriceUnavailableException("Selected shop API is incompatible: " + source, failure);
    }
  }

  public boolean enabled(PriceSource source) {
    var manager = plugin.getServer().getPluginManager();
    return switch (source) {
      case ECONOMYSHOPGUI ->
          manager.isPluginEnabled("EconomyShopGUI")
              || manager.isPluginEnabled("EconomyShopGUI-Premium");
      case SHOPGUIPLUS -> manager.isPluginEnabled("ShopGUIPlus");
      case CONFIG -> false;
    };
  }
}
