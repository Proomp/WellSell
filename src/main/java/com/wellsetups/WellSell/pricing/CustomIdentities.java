package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.api.CustomItemResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class CustomIdentities {
  private record Resolver(int priority, BooleanSupplier enabled, CustomItemResolver resolver) {}

  private volatile List<Resolver> resolvers = List.of();

  public synchronized AutoCloseable register(
      int priority, BooleanSupplier enabled, CustomItemResolver resolver) {
    if (resolvers.size() >= 64) {
      throw new IllegalStateException("Custom resolver limit reached");
    }
    Resolver added =
        new Resolver(
            priority,
            java.util.Objects.requireNonNull(enabled),
            java.util.Objects.requireNonNull(resolver));
    List<Resolver> next = new ArrayList<>(resolvers);
    next.add(added);
    next.sort(Comparator.comparingInt(Resolver::priority).reversed());
    resolvers = List.copyOf(next);
    return () -> unregister(added);
  }

  private synchronized void unregister(Resolver resolver) {
    List<Resolver> next = new ArrayList<>(resolvers);
    next.remove(resolver);
    resolvers = List.copyOf(next);
  }

  public String resolve(ItemStack item, CustomPrices settings) {
    try {
      for (Resolver resolver : resolvers) {
        if (!resolver.enabled().getAsBoolean()) {
          continue;
        }
        Optional<String> identity = resolver.resolver().resolve(item.clone());
        if (identity.isPresent()) {
          return validated(identity.get());
        }
      }
      if (!settings.keys().isEmpty() && item.hasItemMeta()) {
        var data =
            java.util.Objects.requireNonNull(item.getItemMeta()).getPersistentDataContainer();
        for (var key : settings.keys()) {
          String identity = data.get(key, PersistentDataType.STRING);
          if (identity != null) {
            return validated(identity);
          }
        }
      }
      return vanilla(item);
    } catch (RuntimeException | LinkageError failure) {
      throw new PriceUnavailableException("Custom identity resolution failed", failure);
    }
  }

  public static String vanilla(ItemStack item) {
    return "minecraft:" + item.getType().name().toLowerCase(java.util.Locale.ROOT);
  }

  private static String validated(String identity) {
    if (!CustomPrices.valid(identity)) {
      throw new IllegalArgumentException("Resolver returned an invalid custom identity");
    }
    return identity;
  }
}
