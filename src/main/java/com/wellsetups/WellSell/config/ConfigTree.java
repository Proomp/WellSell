package com.wellsetups.WellSell.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only configuration data, safe to publish across region threads. */
public final class ConfigTree {
  private final Map<String, Object> values;

  public ConfigTree(Map<String, Object> values) {
    this.values = Collections.unmodifiableMap(copy(values));
  }

  public Object get(String path) {
    Object current = values;
    for (String part : path.split("\\.")) {
      if (!(current instanceof Map<?, ?> map)) {
        return null;
      }
      current = map.get(part);
    }
    return current;
  }

  public String text(String path) {
    Object value = get(path);
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("Expected text at " + path);
    }
    return text;
  }

  public String text(String path, String fallback) {
    return get(path) instanceof String text ? text : fallback;
  }

  public boolean flag(String path) {
    if (!(get(path) instanceof Boolean value)) {
      throw new IllegalArgumentException("Expected true/false at " + path);
    }
    return value;
  }

  public int integer(String path, int min, int max) {
    int value = decimal(path).intValueExact();
    if (value < min || value > max) {
      throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
    }
    return value;
  }

  public BigDecimal decimal(String path) {
    Object value = get(path);
    if (value == null || value instanceof Boolean) {
      throw new IllegalArgumentException("Expected a decimal at " + path);
    }
    return new BigDecimal(value.toString());
  }

  public List<String> strings(String path) {
    if (!(get(path) instanceof List<?> list)) {
      throw new IllegalArgumentException("Expected a list at " + path);
    }
    return list.stream().map(Object::toString).toList();
  }

  public Map<String, Object> section(String path) {
    Object object = get(path);
    if (!(object instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, value) -> result.put(key.toString(), value));
    return Collections.unmodifiableMap(result);
  }

  public Map<String, Object> values() {
    return values;
  }

  private static Map<String, Object> copy(Map<?, ?> source) {
    Map<String, Object> target = new LinkedHashMap<>();
    source.forEach((key, value) -> target.put(key.toString(), freeze(value)));
    return target;
  }

  private static Object freeze(Object value) {
    if (value instanceof Map<?, ?> map) {
      return Collections.unmodifiableMap(copy(map));
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      list.forEach(element -> result.add(freeze(element)));
      return Collections.unmodifiableList(result);
    }
    return value;
  }
}
