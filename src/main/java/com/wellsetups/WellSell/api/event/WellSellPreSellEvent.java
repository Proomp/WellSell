package com.wellsetups.WellSell.api.event;

import com.wellsetups.WellSell.api.SaleSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Runs on the player's owner thread before journal preparation and all mutations. */
public final class WellSellPreSellEvent extends Event implements Cancellable {
  private static final HandlerList HANDLERS = new HandlerList();
  private final SaleSnapshot sale;
  private boolean cancelled;

  public WellSellPreSellEvent(SaleSnapshot sale, boolean asynchronous) {
    super(asynchronous);
    this.sale = java.util.Objects.requireNonNull(sale);
  }

  public SaleSnapshot sale() {
    return sale;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean value) {
    cancelled = value;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
