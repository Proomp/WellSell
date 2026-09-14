package com.wellsetups.WellSell.api;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** Owner-thread callback receiving a defensive copy. Return a trusted namespaced identity. */
@FunctionalInterface
public interface CustomItemResolver {
  Optional<String> resolve(ItemStack item);
}
