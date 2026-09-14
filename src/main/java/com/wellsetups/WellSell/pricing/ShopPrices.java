package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** All methods run on the calling player's owner thread, or the main thread for imports. */
public interface ShopPrices {
  default ShopQuote quote(Player player, Map<Integer, ItemStack> items) {
    return quote(player, items, false);
  }

  ShopQuote quote(Player player, Map<Integer, ItemStack> items, boolean preventDeniedFallback);

  Optional<BigDecimal> basePrice(Material material);
}
