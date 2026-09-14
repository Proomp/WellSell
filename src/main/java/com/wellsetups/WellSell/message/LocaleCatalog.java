package com.wellsetups.WellSell.message;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class LocaleCatalog {
  private final ConfigTree selected;
  private final ConfigTree english;
  private final ConfigTree bundled;
  private final Consumer<String> warning;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  public LocaleCatalog(
      ConfigTree selected, ConfigTree english, ConfigTree bundled, Consumer<String> warning) {
    this.selected = selected;
    this.english = english;
    this.bundled = bundled;
    this.warning = warning;
  }

  public String text(String key) {
    String path = "messages." + key;
    if (selected.get(path) instanceof String value) {
      return value;
    }
    if (warned.add(key)) {
      warning.accept("Locale key missing or not text: " + path + "; using English fallback.");
    }
    return english.text(path, bundled.text(path, "[" + key + "]"));
  }
}
