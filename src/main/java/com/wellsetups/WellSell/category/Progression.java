package com.wellsetups.WellSell.category;

import com.wellsetups.WellSell.api.CategoryStatistics;
import com.wellsetups.WellSell.api.ProgressionView;
import com.wellsetups.WellSell.config.ConfigTree;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class Progression {
  public enum Requirement {
    MONEY_EARNED,
    ITEMS_SOLD
  }

  public enum Combination {
    MULTIPLY,
    HIGHEST
  }

  public record Tier(int level, BigDecimal required, BigDecimal multiplier) {
    public Tier {
      if (level < 1
          || required.signum() < 0
          || multiplier.signum() <= 0
          || multiplier.compareTo(new BigDecimal("100")) > 0) {
        throw new IllegalArgumentException("Invalid progression tier");
      }
    }
  }

  private static final Tier BASE = new Tier(1, BigDecimal.ZERO, BigDecimal.ONE);
  private final boolean enabled;
  private final Requirement requirement;
  private final Combination combination;
  private final Map<String, List<Tier>> levels;

  public Progression(ConfigTree config, Consumer<String> warning) {
    enabled = config.flag("enabled");
    requirement = Requirement.valueOf(config.text("requirement"));
    combination = Combination.valueOf(config.text("combination"));
    Map<String, List<Tier>> categories = new LinkedHashMap<>();
    config
        .section("categories")
        .forEach(
            (id, ignored) -> {
              try {
                if (!CategoryCatalog.validId(id)) {
                  throw new IllegalArgumentException("Invalid category ID");
                }
                categories.put(
                    id, read(config.section("categories." + id + ".levels"), requirement));
              } catch (IllegalArgumentException failure) {
                warning.accept("Ignored progression for " + id + ": " + failure.getMessage());
              }
            });
    levels = Map.copyOf(categories);
  }

  private static List<Tier> read(Map<String, Object> values, Requirement requirement) {
    List<Tier> tiers = new ArrayList<>();
    values.forEach(
        (number, definition) -> {
          if (!(definition instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("Expected tier mapping");
          }
          BigDecimal threshold = new BigDecimal(String.valueOf(fields.get("required")));
          if (requirement == Requirement.ITEMS_SOLD && threshold.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Item thresholds must be whole numbers");
          }
          tiers.add(
              new Tier(
                  Integer.parseInt(number),
                  threshold,
                  new BigDecimal(String.valueOf(fields.get("multiplier")))));
        });
    tiers.sort(Comparator.comparingInt(Tier::level));
    if (tiers.isEmpty() || tiers.get(0).required().signum() != 0) {
      throw new IllegalArgumentException("First tier must require zero progress");
    }
    for (int index = 1; index < tiers.size(); index++) {
      if (tiers.get(index).required().compareTo(tiers.get(index - 1).required()) <= 0) {
        throw new IllegalArgumentException("Tier thresholds must strictly increase");
      }
    }
    return List.copyOf(tiers);
  }

  public boolean enabled() {
    return enabled;
  }

  public Requirement requirement() {
    return requirement;
  }

  public ProgressionView view(String category, CategoryStatistics statistics) {
    List<Tier> tiers = enabled ? levels.getOrDefault(category, List.of(BASE)) : List.of(BASE);
    BigDecimal value =
        requirement == Requirement.MONEY_EARNED
            ? statistics.earned()
            : BigDecimal.valueOf(statistics.items());
    int index = 0;
    while (index + 1 < tiers.size() && value.compareTo(tiers.get(index + 1).required()) >= 0) {
      index++;
    }
    Tier current = tiers.get(index);
    boolean maximum = index + 1 == tiers.size();
    Tier next = maximum ? current : tiers.get(index + 1);
    BigDecimal percent =
        maximum
            ? new BigDecimal("100")
            : value
                .subtract(current.required())
                .multiply(new BigDecimal("100"))
                .divide(next.required().subtract(current.required()), 2, RoundingMode.DOWN)
                .max(BigDecimal.ZERO)
                .min(new BigDecimal("100"));
    return new ProgressionView(
        current.level(),
        current.multiplier(),
        percent,
        current.required(),
        next.required(),
        next.multiplier(),
        maximum);
  }

  public BigDecimal combine(BigDecimal permission, BigDecimal category) {
    BigDecimal result =
        combination == Combination.HIGHEST
            ? permission.max(category)
            : permission.multiply(category);
    if (result.signum() <= 0 || result.compareTo(new BigDecimal("100")) > 0) {
      throw new IllegalArgumentException("Combined multiplier exceeds the safety limit");
    }
    return result;
  }
}
