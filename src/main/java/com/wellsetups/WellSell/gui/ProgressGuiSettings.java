package com.wellsetups.WellSell.gui;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public record ProgressGuiSettings(
    boolean enabled,
    String title,
    int size,
    List<Integer> slots,
    Map<Button, Integer> buttons,
    Map<Button, GuiSettings.Icon> icons,
    GuiSettings.Icon category,
    GuiSettings.Icon filler,
    boolean categoryMaterial,
    int barWidth,
    String filled,
    String empty,
    String barFormat) {
  public enum Button {
    PREVIOUS,
    NEXT,
    BACK,
    INFO
  }

  public ProgressGuiSettings {
    slots = List.copyOf(slots);
    buttons = Map.copyOf(buttons);
    icons = Map.copyOf(icons);
  }

  public static ProgressGuiSettings load(ConfigTree config, Consumer<String> warning) {
    int size = config.integer("gui.rows", 1, 6) * 9;
    List<Integer> slots =
        config.strings("gui.category-slots").stream().map(Integer::parseInt).toList();
    var used = new HashSet<Integer>();
    if (slots.isEmpty()) {
      throw new IllegalArgumentException("Progress menu needs category slots");
    }
    for (int slot : slots) {
      validateSlot(slot, size, used);
    }
    var buttons = new EnumMap<Button, Integer>(Button.class);
    var icons = new EnumMap<Button, GuiSettings.Icon>(Button.class);
    for (Button button : Button.values()) {
      String key = button.name().toLowerCase(Locale.ROOT);
      int slot = config.integer("gui." + key + ".slot", 0, size - 1);
      validateSlot(slot, size, used);
      buttons.put(button, slot);
      icons.put(button, GuiSettings.icon(config, key, warning));
    }
    String filled = config.text("gui.progress-bar.filled");
    String empty = config.text("gui.progress-bar.empty");
    if (filled.length() > 64 || empty.length() > 64) {
      throw new IllegalArgumentException("Progress bar character is too long");
    }
    return new ProgressGuiSettings(
        config.flag("gui.enabled"),
        config.text("gui.title"),
        size,
        slots,
        buttons,
        icons,
        GuiSettings.icon(config, "category", warning),
        GuiSettings.icon(config, "filler", warning),
        config.flag("gui.category.use-category-icon"),
        config.integer("gui.progress-bar.width", 1, 40),
        filled,
        empty,
        config.text("gui.progress-bar.format"));
  }

  private static void validateSlot(int slot, int size, HashSet<Integer> used) {
    if (slot < 0 || slot >= size || !used.add(slot)) {
      throw new IllegalArgumentException("Invalid or overlapping progress menu slot " + slot);
    }
  }

  public String bar(java.math.BigDecimal percent) {
    int count =
        percent
            .multiply(java.math.BigDecimal.valueOf(barWidth))
            .divideToIntegralValue(new java.math.BigDecimal("100"))
            .intValue();
    count = Math.max(0, Math.min(barWidth, count));
    return barFormat
        .replace("%filled%", filled.repeat(count))
        .replace("%empty%", empty.repeat(barWidth - count));
  }
}
