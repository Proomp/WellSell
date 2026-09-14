package com.wellsetups.WellSell.gui;

import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.message.SoundEffect;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;

public record GuiSettings(
    boolean enabled,
    String title,
    int size,
    List<Integer> slots,
    int confirmSlot,
    int cancelSlot,
    int infoSlot,
    Icon confirm,
    Icon cancel,
    Icon info,
    Icon filler,
    SoundEffect click) {
  public record Icon(
      Material material,
      int amount,
      int modelData,
      boolean glow,
      String name,
      List<String> lore,
      SoundEffect sound) {
    public Icon {
      lore = List.copyOf(lore);
    }
  }

  public GuiSettings {
    slots = List.copyOf(slots);
  }

  public GuiSettings withEnabled(boolean value) {
    return new GuiSettings(
        value,
        title,
        size,
        slots,
        confirmSlot,
        cancelSlot,
        infoSlot,
        confirm,
        cancel,
        info,
        filler,
        click);
  }

  public static GuiSettings load(ConfigTree config, Consumer<String> warning) {
    int size = config.integer("gui.rows", 1, 6) * 9;
    int confirm = config.integer("gui.confirm.slot", 0, size - 1);
    int cancel = config.integer("gui.cancel.slot", 0, size - 1);
    int info = config.integer("gui.info.slot", 0, size - 1);
    List<Integer> slots = config.strings("gui.sell-slots").stream().map(Integer::parseInt).toList();
    Set<Integer> used = new HashSet<>();
    for (int slot : List.of(confirm, cancel, info)) {
      if (!used.add(slot)) {
        throw new IllegalArgumentException("GUI button slots overlap");
      }
    }
    if (slots.isEmpty()) {
      throw new IllegalArgumentException("gui.sell-slots must contain at least one slot");
    }
    for (int slot : slots) {
      if (slot < 0 || slot >= size || !used.add(slot)) {
        throw new IllegalArgumentException("Invalid or overlapping gui.sell-slots entry: " + slot);
      }
    }
    return new GuiSettings(
        config.flag("gui.enabled"),
        config.text("gui.title"),
        size,
        slots,
        confirm,
        cancel,
        info,
        icon(config, "confirm", warning),
        icon(config, "cancel", warning),
        icon(config, "info", warning),
        icon(config, "filler", warning),
        SoundEffect.load(config, "gui.click-sound", warning));
  }

  static Icon icon(ConfigTree config, String name, Consumer<String> warning) {
    String path = "gui." + name;
    Material material = Material.matchMaterial(config.text(path + ".material"));
    if (material == null || !material.isItem() || material.isAir()) {
      warning.accept("Invalid " + path + ".material; using BARRIER");
      material = Material.BARRIER;
    }
    return new Icon(
        material,
        config.integer(path + ".amount", 1, 64),
        config.integer(path + ".custom-model-data", 0, Integer.MAX_VALUE),
        config.flag(path + ".glow"),
        config.text(path + ".name"),
        config.strings(path + ".lore"),
        SoundEffect.load(config, path + ".sound", warning));
  }
}
