package com.wellsetups.WellSell.lore;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.List;

public record LoreSettings(boolean enabled, boolean unit, int cacheSeconds, List<String> lines) {
  public LoreSettings {
    lines = List.copyOf(lines);
    if (lines.size() > 8 || lines.stream().anyMatch(line -> line.length() > 512)) {
      throw new IllegalArgumentException("Worth lore allows at most 8 lines of 512 characters");
    }
  }

  public static LoreSettings disabled() {
    return new LoreSettings(false, false, 5, List.of());
  }

  public static LoreSettings load(ConfigTree config) {
    return new LoreSettings(
        config.flag("enabled"),
        config.flag("show-unit-price"),
        config.integer("cache-seconds", 1, 60),
        config.strings("lines"));
  }
}
