package com.wellsetups.WellSell.lore;

import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.pricing.SalePricing;
import com.wellsetups.WellSell.sell.BukkitInventoryAccess;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Owner-thread-only conversion; modifies a clone, never the real inventory stack. */
public final class LoreRenderer {
  public record Pair(
      com.github.retrooper.packetevents.protocol.item.ItemStack original,
      com.github.retrooper.packetevents.protocol.item.ItemStack display) {}

  private final SalePricing pricing;
  private final Messages messages;

  public LoreRenderer(SalePricing pricing, Messages messages) {
    this.pricing = pricing;
    this.messages = messages;
  }

  public Pair prepare(Player player, ItemStack original, Settings settings) {
    var packet = SpigotConversionUtil.fromBukkitItemStack(original.clone());
    try {
      var quote = quote(player, original, settings);
      if (quote.money().signum() <= 0) {
        return new Pair(packet, packet);
      }
      ItemStack copy = original.clone();
      var meta = copy.getItemMeta();
      if (meta == null) {
        return new Pair(packet, packet);
      }
      var lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
      Map<String, String> values = new HashMap<>(messages.sale(quote));
      values.put("provider", quote.summary().provider());
      if (settings.expansion().lore().unit()) {
        ItemStack unit = original.clone();
        unit.setAmount(1);
        values.put("unit_money", settings.formatMoney(quote(player, unit, settings).money()));
      } else {
        values.put("unit_money", "");
      }
      for (String line : settings.expansion().lore().lines()) {
        if (!line.contains("%unit_money%") || settings.expansion().lore().unit()) {
          lore.add(messages.render(line, values));
        }
      }
      meta.setLore(lore);
      copy.setItemMeta(meta);
      return new Pair(
          packet, LoreMarker.mark(packet, SpigotConversionUtil.fromBukkitItemStack(copy)));
    } catch (com.wellsetups.WellSell.pricing.PriceUnavailableException
        | IllegalArgumentException unavailable) {
      return new Pair(packet, packet);
    }
  }

  private com.wellsetups.WellSell.sell.SellPlan quote(
      Player player, ItemStack item, Settings settings) {
    var inventory =
        new BukkitInventoryAccess(
            player, Map.of(0, new BukkitInventoryAccess.Selection(item, item.getAmount())));
    return pricing.quote(player, inventory, settings).plan();
  }
}
