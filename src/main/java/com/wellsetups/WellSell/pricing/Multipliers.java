package com.wellsetups.WellSell.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public record Multipliers(boolean enabled, Selection selection, List<Group> groups) {
  public enum Selection {
    HIGHEST,
    LOWEST,
    FIRST_PRIORITY
  }

  public record Group(String permission, BigDecimal multiplier) {
    public Group {
      Objects.requireNonNull(permission);
      Objects.requireNonNull(multiplier);
      if (permission.isBlank()
          || multiplier.signum() <= 0
          || multiplier.compareTo(new BigDecimal("100")) > 0
          || multiplier.scale() > 6) {
        throw new IllegalArgumentException("Multiplier must be > 0 and <= 100 with <= 6 decimals");
      }
    }
  }

  public Multipliers {
    Objects.requireNonNull(selection);
    groups = List.copyOf(groups);
  }

  public BigDecimal select(Predicate<String> permission) {
    BigDecimal chosen = null;
    if (enabled) {
      for (Group group : groups) {
        if (!permission.test(group.permission())) {
          continue;
        }
        if (chosen == null
            || selection == Selection.HIGHEST && group.multiplier().compareTo(chosen) > 0
            || selection == Selection.LOWEST && group.multiplier().compareTo(chosen) < 0) {
          chosen = group.multiplier();
        }
        if (selection == Selection.FIRST_PRIORITY) {
          break;
        }
      }
    }
    return chosen == null ? BigDecimal.ONE : chosen;
  }
}
