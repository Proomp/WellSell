package com.wellsetups.WellSell.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/** InventoryView changed from an abstract class to an interface in 1.21. */
public final class InventoryViews {
  private final MethodHandle top;

  public InventoryViews() {
    try {
      top =
          MethodHandles.publicLookup()
              .findVirtual(
                  InventoryView.class, "getTopInventory", MethodType.methodType(Inventory.class))
              .asType(MethodType.methodType(Inventory.class, Object.class));
    } catch (NoSuchMethodException | IllegalAccessException failure) {
      throw new IllegalStateException("InventoryView capability unavailable", failure);
    }
  }

  public Inventory top(Object view) {
    try {
      return (Inventory) top.invokeExact(view);
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failure) {
      // MethodHandle requires Throwable handling even though this Bukkit getter does
      // not declare checked exceptions. No fallback inventory can be safely invented.
      throw new IllegalStateException("Could not read InventoryView", failure);
    }
  }
}
