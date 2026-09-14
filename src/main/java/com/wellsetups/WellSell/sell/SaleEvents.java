package com.wellsetups.WellSell.sell;

import com.wellsetups.WellSell.api.SaleSnapshot;
import com.wellsetups.WellSell.api.event.WellSellPostSellEvent;
import com.wellsetups.WellSell.api.event.WellSellPreSellEvent;
import com.wellsetups.WellSell.storage.SaleRecord;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.event.Event;

public final class SaleEvents {
  private final Consumer<Event> dispatch;
  private final BooleanSupplier asynchronous;
  private final Consumer<Runnable> global;

  public SaleEvents(
      Consumer<Event> dispatch, BooleanSupplier asynchronous, Consumer<Runnable> global) {
    this.dispatch = dispatch;
    this.asynchronous = asynchronous;
    this.global = global;
  }

  public boolean before(SaleRecord sale) {
    var event = new WellSellPreSellEvent(snapshot(sale), asynchronous.getAsBoolean());
    dispatch.accept(event);
    return !event.isCancelled();
  }

  public void finished(SaleRecord sale, String state, Throwable failure) {
    if (failure == null && state.equals("SUCCESS")) {
      global.accept(
          () ->
              dispatch.accept(
                  new WellSellPostSellEvent(snapshot(sale), asynchronous.getAsBoolean())));
    }
  }

  private static SaleSnapshot snapshot(SaleRecord sale) {
    return new SaleSnapshot(
        sale.id(),
        sale.player(),
        sale.playerName(),
        sale.time(),
        sale.amount(),
        sale.money(),
        sale.multiplier(),
        sale.source(),
        sale.summary());
  }
}
