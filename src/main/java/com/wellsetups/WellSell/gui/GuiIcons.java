package com.wellsetups.WellSell.gui;

import com.wellsetups.WellSell.message.Messages;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class GuiIcons {
  private final Messages messages;

  GuiIcons(Messages messages) {
    this.messages = messages;
  }

  ItemStack create(GuiSettings.Icon icon, Map<String, String> values) {
    return create(icon, values, Map.of());
  }

  ItemStack create(GuiSettings.Icon icon, Map<String, String> values, Map<String, String> trusted) {
    ItemStack item = new ItemStack(icon.material(), icon.amount());
    ItemMeta meta = item.getItemMeta();
    if (meta == null) {
      throw new IllegalStateException("Configured GUI icon has no item metadata");
    }
    meta.setDisplayName(render(icon.name(), values, trusted));
    meta.setLore(icon.lore().stream().map(line -> render(line, values, trusted)).toList());
    if (icon.modelData() != 0) {
      meta.setCustomModelData(icon.modelData());
    }
    if (icon.glow()) {
      Enchantment glow = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
      if (glow != null) {
        meta.addEnchant(glow, 1, true);
      }
      meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }
    item.setItemMeta(meta);
    return item;
  }

  private String render(String template, Map<String, String> values, Map<String, String> trusted) {
    String expanded = template;
    for (var entry : trusted.entrySet()) {
      expanded = expanded.replace("%" + entry.getKey() + "%", entry.getValue());
    }
    return messages.render(expanded, values);
  }
}
