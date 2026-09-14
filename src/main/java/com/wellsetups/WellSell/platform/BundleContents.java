package com.wellsetups.WellSell.platform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Detects the optional Bukkit bundle surface once; contains no NMS or reflective lookup loop. */
public final class BundleContents {
  private final Class<?> type;
  private final MethodHandle read;
  private final MethodHandle write;

  public BundleContents() {
    Class<?> found;
    MethodHandle getter;
    MethodHandle setter;
    try {
      found =
          Class.forName("org.bukkit.inventory.meta.BundleMeta", false, getClass().getClassLoader());
      getter = MethodHandles.publicLookup().unreflect(found.getMethod("getItems"));
      setter = MethodHandles.publicLookup().unreflect(found.getMethod("setItems", List.class));
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException missing) {
      found = null;
      getter = null;
      setter = null;
    }
    type = found;
    read = getter;
    write = setter;
  }

  public boolean supports(ItemMeta meta) {
    return type != null && type.isInstance(meta);
  }

  public List<ItemStack> items(ItemMeta meta) {
    try {
      Object value = read.invoke(meta);
      if (!(value instanceof List<?> list)) {
        throw new IllegalStateException("Unexpected bundle contents");
      }
      return list.stream().map(item -> ((ItemStack) item).clone()).toList();
    } catch (Throwable failure) {
      throw new IllegalStateException("Bukkit bundle contents are unavailable", failure);
    }
  }

  public void emptyCopy(ItemMeta copy) {
    try {
      write.invoke(copy, List.of());
    } catch (Throwable failure) {
      throw new IllegalStateException("Cannot prepare a bundle display copy", failure);
    }
  }
}
