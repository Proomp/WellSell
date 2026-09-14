package com.wellsetups.WellSell.sell;

import java.util.Objects;

/** An eligible quantity in a specific storage slot; metadata is retained by the inventory port. */
public record Stock(int slot, String material, int available, int requested) {
  public Stock {
    Objects.requireNonNull(material);
    if (slot < 0
        || slot >= 36
        || available < 1
        || available > 127
        || requested < 1
        || requested > available) {
      throw new IllegalArgumentException("Invalid slot or item quantity");
    }
  }
}
