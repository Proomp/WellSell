package com.wellsetups.WellSell.category;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class CategoryCatalog {
  public enum Resolution {
    FIRST_PRIORITY,
    REQUIRE_UNIQUE
  }

  public record Category(String id, String displayName, String icon, List<String> identities) {
    public Category {
      identities = List.copyOf(identities);
    }
  }

  private final Map<String, Category> categories;
  private final Map<String, String> assignment;
  private final Set<String> ambiguous;
  private final String fallback;

  public CategoryCatalog(
      ConfigTree config, Predicate<String> materialExists, Consumer<String> warning) {
    Resolution resolution = Resolution.valueOf(config.text("resolution"));
    Map<String, Category> definitions = new LinkedHashMap<>();
    Map<String, String> index = new LinkedHashMap<>();
    Set<String> conflicts = new HashSet<>();
    for (String id : config.section("categories").keySet()) {
      try {
        Category category = read(config, id, materialExists, warning);
        if (category != null) {
          definitions.put(id, category);
          for (String identity : category.identities()) {
            String previous = index.putIfAbsent(identity, id);
            if (previous != null && !previous.equals(id)) {
              warning.accept("Category overlap for " + identity + ": " + previous + ", " + id);
              if (resolution == Resolution.REQUIRE_UNIQUE) {
                conflicts.add(identity);
              }
            }
          }
        }
      } catch (IllegalArgumentException failure) {
        warning.accept("Ignored category " + id + ": " + failure.getMessage());
      }
    }
    if (definitions.size() > 128) {
      throw new IllegalArgumentException("At most 128 enabled categories are supported");
    }
    categories = java.util.Collections.unmodifiableMap(definitions);
    assignment = Map.copyOf(index);
    ambiguous = Set.copyOf(conflicts);
    String configuredFallback = config.text("uncategorized");
    fallback = definitions.containsKey(configuredFallback) ? configuredFallback : "";
    if (!configuredFallback.isEmpty() && fallback.isEmpty()) {
      warning.accept("Unknown fallback category: " + configuredFallback);
    }
  }

  private static Category read(
      ConfigTree config, String id, Predicate<String> materialExists, Consumer<String> warning) {
    if (!validId(id)) {
      throw new IllegalArgumentException("Use lowercase letters, digits and underscores");
    }
    String path = "categories." + id;
    if (!config.flag(path + ".enabled")) {
      return null;
    }
    List<String> identities =
        config.strings(path + ".materials").stream()
            .map(CategoryCatalog::normalize)
            .filter(
                identity -> {
                  boolean valid =
                      com.wellsetups.WellSell.pricing.CustomPrices.valid(identity)
                          || materialExists.test(identity);
                  if (!valid) {
                    warning.accept("Ignored material " + identity + " in category " + id);
                  }
                  return valid;
                })
            .distinct()
            .toList();
    return new Category(
        id, config.text(path + ".display-name"), config.text(path + ".icon"), identities);
  }

  private static String normalize(String identity) {
    if (identity.startsWith("minecraft:")) {
      return identity.substring("minecraft:".length()).toUpperCase(java.util.Locale.ROOT);
    }
    return identity.contains(":") ? identity : identity.toUpperCase(java.util.Locale.ROOT);
  }

  public static boolean validId(String id) {
    return id.matches("[a-z][a-z0-9_]{0,31}");
  }

  public Optional<Category> resolve(String identity, String material) {
    identity = normalize(identity);
    material = normalize(material);
    if (ambiguous.contains(identity) || ambiguous.contains(material)) {
      return Optional.empty();
    }
    String id = assignment.getOrDefault(identity, assignment.getOrDefault(material, fallback));
    return Optional.ofNullable(categories.get(id));
  }

  public Map<String, Category> categories() {
    return categories;
  }
}
