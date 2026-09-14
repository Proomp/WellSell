package com.wellsetups.WellSell.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DefaultMerger {
  private DefaultMerger() {}

  /** Open maps are administrator-owned collections, not fixed configuration schemas. */
  public static Map<String, Object> merge(
      Map<String, Object> custom, Map<String, Object> defaults, Set<String> openMaps) {
    return mergeAt(custom, defaults, openMaps, "");
  }

  private static Map<String, Object> mergeAt(
      Map<String, Object> custom, Map<String, Object> defaults, Set<String> openMaps, String path) {
    Map<String, Object> result = new LinkedHashMap<>(custom);
    defaults.forEach(
        (key, fallback) -> {
          String child = path.isEmpty() ? key : path + "." + key;
          if (!custom.containsKey(key)) {
            result.put(key, fallback);
          } else if (!openMaps.contains(child)
              && custom.get(key) instanceof Map<?, ?> existing
              && fallback instanceof Map<?, ?> template) {
            result.put(key, mergeAt(stringMap(existing), stringMap(template), openMaps, child));
          }
        });
    return result;
  }

  static Map<String, Object> stringMap(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          if (!(key instanceof String text)) {
            throw new IllegalArgumentException("YAML mapping keys must be text: " + key);
          }
          result.put(text, value);
        });
    return result;
  }
}
