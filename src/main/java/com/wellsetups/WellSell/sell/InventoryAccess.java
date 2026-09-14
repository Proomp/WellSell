package com.wellsetups.WellSell.sell;

/**
 * All methods run on the owner's entity thread. Implementations retain exact immutable snapshots.
 */
public interface InventoryAccess {
  boolean unchanged(SellPlan plan);

  /** Remove all plan lines, or restore already removed lines before throwing. */
  void remove(SellPlan plan);

  /** Return removed quantities without overwriting unrelated inventory changes. */
  void restore(SellPlan plan);
}
