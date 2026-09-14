package com.wellsetups.WellSell.api.event;

import com.wellsetups.WellSell.api.SaleSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Runs on the global/main scheduler after confirmed payment and durable history; never replayed.
 */
public final class WellSellPostSellEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final SaleSnapshot sale;

  public WellSellPostSellEvent(SaleSnapshot sale, boolean asynchronous) {
    super(asynchronous);
    this.sale = java.util.Objects.requireNonNull(sale);
  }

  public SaleSnapshot sale() {
    return sale;
  }

  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
