package com.wellsetups.WellSell.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.api.event.WellSellPostSellEvent;
import com.wellsetups.WellSell.api.event.WellSellPreSellEvent;
import com.wellsetups.WellSell.storage.SaleRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

class SaleEventsTest {
  @Test
  void cancellablePreEventExposesOnlyImmutableSaleData() {
    var events =
        new SaleEvents(
            event -> ((WellSellPreSellEvent) event).setCancelled(true), () -> false, Runnable::run);
    assertFalse(events.before(sale()));
    var event =
        new WellSellPreSellEvent(
            new com.wellsetups.WellSell.api.SaleSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tester",
                Instant.now(),
                1,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "HAND",
                com.wellsetups.WellSell.api.SaleSummary.empty()),
            false);
    assertFalse(event.isAsynchronous());
    assertFalse(event.isCancelled());
    event.setCancelled(true);
    assertTrue(event.isCancelled());
    assertEquals(1, event.sale().amount());
  }

  @Test
  void postEventRequiresBothSuccessfulPaymentStateAndSuccessfulPersistence() {
    var sent = new ArrayList<Event>();
    var scheduled = new ArrayList<Runnable>();
    var events = new SaleEvents(sent::add, () -> true, scheduled::add);
    for (String state : new String[] {"CANCELLED", "REJECTED", "UNCERTAIN", "CHANGED"}) {
      events.finished(sale(), state, null);
    }
    events.finished(sale(), "SUCCESS", new IllegalStateException("Database unavailable"));
    assertTrue(scheduled.isEmpty());
    SaleRecord sale = sale();
    events.finished(sale, "SUCCESS", null);
    assertTrue(sent.isEmpty());
    scheduled.get(0).run();
    assertEquals(1, sent.size());
    assertEquals(sale.id(), ((WellSellPostSellEvent) sent.get(0)).sale().transaction());
    assertTrue(sent.get(0).isAsynchronous());
  }

  private static SaleRecord sale() {
    return new SaleRecord(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "Tester",
        Instant.now(),
        1,
        BigDecimal.ONE,
        BigDecimal.ONE,
        "HAND",
        "");
  }
}
