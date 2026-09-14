package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.pricing.ShopPrices;
import com.wellsetups.WellSell.pricing.ShopQuote;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import me.gypopo.economyshopgui.api.EconomyShopGUIHook;
import me.gypopo.economyshopgui.objects.ShopItem;
import me.gypopo.economyshopgui.util.EconomyType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class EconomyShopPrices implements ShopPrices {
  @Override
  public ShopQuote quote(
      Player player, Map<Integer, ItemStack> items, boolean preventDeniedFallback) {
    Map<Integer, ItemStack> accepted = new LinkedHashMap<>();
    Map<Integer, ShopItem> definitions = new LinkedHashMap<>();
    items.forEach(
        (slot, item) -> {
          ShopItem shop = EconomyShopGUIHook.getShopItem(player, item);
          if (eligible(shop)) {
            accepted.put(slot, item.clone());
            definitions.put(slot, shop);
          } else if (preventDeniedFallback && EconomyShopGUIHook.getShopItem(item) != null) {
            throw new PriceUnavailableException(
                "EconomyShopGUI denies this item; fallback is disabled");
          }
        });
    if (accepted.isEmpty()) {
      return ShopQuote.empty();
    }
    var quote =
        EconomyShopGUIHook.getSellPrices(player, accepted.values().toArray(ItemStack[]::new));
    int requested = accepted.values().stream().mapToInt(ItemStack::getAmount).sum();
    int allowed = quote.getItems().values().stream().mapToInt(Integer::intValue).sum();
    // A partial batch cannot identify exact accepted slots safely. Refuse it intact.
    if (requested != allowed) {
      throw new PriceUnavailableException(
          "Shop limits reject part of the selection; sell a smaller batch");
    }
    var vault = EconomyType.getFromString("VAULT");
    if (quote.getPrices().size() != 1 || !quote.getPrices().containsKey(vault)) {
      throw new PriceUnavailableException("Only single-currency Vault shop prices are supported");
    }
    return new ShopQuote(
        accepted.keySet(),
        ShopQuote.decimal(quote.getPrice(vault)),
        weights(player, accepted, definitions),
        quote::updateLimits);
  }

  private static Map<Integer, BigDecimal> weights(
      Player player, Map<Integer, ItemStack> items, Map<Integer, ShopItem> definitions) {
    Map<ShopItem, Integer> sold = new java.util.HashMap<>();
    Map<Integer, BigDecimal> values = new LinkedHashMap<>();
    items.forEach(
        (slot, item) -> {
          ShopItem shop = definitions.get(slot);
          Double value =
              EconomyShopGUIHook.getItemSellPrice(
                  shop, item, player, item.getAmount(), sold.getOrDefault(shop, 0));
          if (value == null) {
            throw new PriceUnavailableException("Missing category attribution price");
          }
          values.put(slot, ShopQuote.decimal(value));
          sold.merge(shop, item.getAmount(), Math::addExact);
        });
    return values;
  }

  private static boolean eligible(ShopItem shop) {
    return shop != null
        && !shop.hasItemError()
        && !shop.isDisplayItem()
        && EconomyShopGUIHook.isSellAble(shop);
  }

  @Override
  public Optional<BigDecimal> basePrice(Material material) {
    ItemStack item = new ItemStack(material);
    ShopItem shop = EconomyShopGUIHook.getShopItem(item);
    if (!eligible(shop)
        || EconomyShopGUIHook.hasMultipleSellPrices(shop)
        || !EconomyType.getFromString("VAULT").equals(shop.getEcoType())
        || shop.isDynamicPricing()
        || shop.getLimitedSellMode() != 0
        || shop.isRefillStock()
        || shop.isMaxSell(1)) {
      return Optional.empty();
    }
    Double price = EconomyShopGUIHook.getItemSellPrice(shop, item, 1, 0);
    return price == null || price <= 0 ? Optional.empty() : Optional.of(ShopQuote.decimal(price));
  }
}
