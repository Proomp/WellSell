package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.pricing.ShopPrices;
import com.wellsetups.WellSell.pricing.ShopQuote;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.brcdev.shopgui.ShopGuiPlugin;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class ShopGuiPrices implements ShopPrices {
  private static void requireReady() {
    if (!ShopGuiPlugin.getInstance().getShopManager().areShopsLoaded()) {
      throw new PriceUnavailableException("ShopGUI+ shops have not finished loading");
    }
  }

  private static boolean vault(ShopItem item) {
    return item != null && item.getShop().getEconomyType().name().equals("VAULT");
  }

  @Override
  public ShopQuote quote(
      Player player, Map<Integer, ItemStack> items, boolean preventDeniedFallback) {
    requireReady();
    Set<Integer> slots = new LinkedHashSet<>();
    Map<Integer, BigDecimal> weights = new java.util.LinkedHashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    for (var entry : items.entrySet()) {
      ItemStack item = entry.getValue().clone();
      ShopItem shop = ShopGuiPlusApi.getItemStackShopItem(player, item);
      if (!vault(shop)) {
        if (preventDeniedFallback && ShopGuiPlusApi.getItemStackShopItem(item) != null) {
          throw new PriceUnavailableException(
              "ShopGUI+ denies this item or its currency; fallback is disabled");
        }
        continue;
      }
      double price = ShopGuiPlusApi.getItemStackPriceSell(player, item);
      if (price > 0) {
        total = total.add(ShopQuote.decimal(price));
        slots.add(entry.getKey());
        weights.put(entry.getKey(), ShopQuote.decimal(price));
      } else if (preventDeniedFallback || !Double.isFinite(price)) {
        throw new PriceUnavailableException("ShopGUI+ returned a non-finite price");
      }
    }
    return new ShopQuote(slots, total, weights, () -> {});
  }

  @Override
  public Optional<BigDecimal> basePrice(Material material) {
    requireReady();
    ItemStack item = new ItemStack(material);
    if (!vault(ShopGuiPlusApi.getItemStackShopItem(item))) {
      return Optional.empty();
    }
    double price = ShopGuiPlusApi.getItemStackPriceSell(item);
    return price <= 0 ? Optional.empty() : Optional.of(ShopQuote.decimal(price));
  }
}
