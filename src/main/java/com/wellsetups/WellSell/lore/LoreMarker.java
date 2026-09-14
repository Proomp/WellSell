package com.wellsetups.WellSell.lore;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;

/** Packet-copy marker. It is never added to Bukkit inventory items or used as identity. */
public final class LoreMarker {
  static final String KEY = "wellsell_display_lore";

  private LoreMarker() {}

  public static ItemStack mark(ItemStack original, ItemStack decorated) {
    var data = original.getComponent(ComponentTypes.CUSTOM_DATA);
    if (data.isPresent() && data.get().getTagOrNull(KEY) != null) {
      return original.copy();
    }
    var lore = original.getComponent(ComponentTypes.LORE);
    int count = lore.map(value -> value.getLines().size()).orElse(0);
    int flags = (lore.isPresent() ? 1 : 0) | (data.isPresent() ? 2 : 0);
    ItemStack result = decorated.copy();
    NBTCompound copy = data.map(NBTCompound::copy).orElseGet(NBTCompound::new);
    copy.setTag(KEY, new NBTInt(count * 4 + flags));
    result.setComponent(ComponentTypes.CUSTOM_DATA, copy);
    return result;
  }

  /** Strips only our appended suffix; malformed markers must reject the creative submission. */
  public static ItemStack strip(ItemStack incoming) {
    var data = incoming.getComponent(ComponentTypes.CUSTOM_DATA);
    if (data.isEmpty() || data.get().getTagOrNull(KEY) == null) {
      return incoming;
    }
    if (!(data.get().getTagOrNull(KEY) instanceof NBTInt marker)) {
      throw new IllegalArgumentException("Malformed client display marker");
    }
    int value = marker.getAsInt();
    int count = value >>> 2;
    var lore = incoming.getComponent(ComponentTypes.LORE);
    if (value < 0 || lore.isEmpty() || count > lore.get().getLines().size()) {
      throw new IllegalArgumentException("Invalid client display lore length");
    }
    ItemStack result = incoming.copy();
    // Pass PacketEvents' own component objects through unchanged. No shaded Adventure values.
    if ((value & 1) != 0) {
      result.setComponent(
          ComponentTypes.LORE,
          new ItemLore(java.util.List.copyOf(lore.get().getLines().subList(0, count))));
    } else {
      result.setComponent(ComponentTypes.LORE, java.util.Optional.empty());
    }
    NBTCompound clean = data.get().copy();
    clean.removeTag(KEY);
    if (clean.isEmpty() && (value & 2) == 0) {
      result.setComponent(ComponentTypes.CUSTOM_DATA, java.util.Optional.empty());
    } else {
      result.setComponent(ComponentTypes.CUSTOM_DATA, clean);
    }
    return result;
  }
}
