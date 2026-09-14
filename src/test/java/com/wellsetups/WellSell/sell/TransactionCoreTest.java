package com.wellsetups.WellSell.sell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wellsetups.WellSell.economy.PaymentResult;
import com.wellsetups.WellSell.pricing.MoneyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransactionCoreTest {
  private final List<RuntimeException> errors = new ArrayList<>();
  private final TransactionCore engine = new TransactionCore(errors::add);
  private final SellPlan plan =
      SellPlan.create(
          List.of(new Stock(0, "DIAMOND", 10, 3)),
          material -> Optional.of(BigDecimal.TEN),
          new MoneyPolicy(2, RoundingMode.HALF_UP, new BigDecimal("10000")),
          BigDecimal.ONE);

  private static final class Inventory implements InventoryAccess {
    private int items = 10;
    private boolean changed;
    private boolean restorationFails;

    @Override
    public boolean unchanged(SellPlan plan) {
      return !changed;
    }

    @Override
    public void remove(SellPlan plan) {
      items -= plan.amount();
    }

    @Override
    public void restore(SellPlan plan) {
      if (restorationFails) {
        throw new IllegalStateException("Inventory adapter failed");
      }
      items += plan.amount();
    }
  }

  @Test
  void successfulSalePaysOnceAfterRemoval() {
    Inventory inventory = new Inventory();
    AtomicInteger deposits = new AtomicInteger();
    assertEquals(
        TransactionCore.Outcome.SUCCESS,
        engine.commit(
            plan,
            inventory,
            () -> {
              assertEquals(7, inventory.items);
              deposits.incrementAndGet();
              return PaymentResult.SUCCESS;
            }));
    assertEquals(1, deposits.get());
    assertEquals(7, inventory.items);
  }

  @Test
  void changedInventoryNeverPaysOrRemoves() {
    Inventory inventory = new Inventory();
    inventory.changed = true;
    AtomicInteger deposits = new AtomicInteger();
    assertEquals(
        TransactionCore.Outcome.CHANGED,
        engine.commit(
            plan,
            inventory,
            () -> {
              deposits.incrementAndGet();
              return PaymentResult.SUCCESS;
            }));
    assertEquals(0, deposits.get());
    assertEquals(10, inventory.items);
  }

  @Test
  void definiteRejectionRestoresRemovedQuantity() {
    Inventory inventory = new Inventory();
    assertEquals(
        TransactionCore.Outcome.REJECTED,
        engine.commit(plan, inventory, () -> PaymentResult.REJECTED));
    assertEquals(10, inventory.items);
  }

  @Test
  void thrownProviderFailureIsNeverAutomaticallyCompensatedOrRetried() {
    Inventory inventory = new Inventory();
    AtomicInteger deposits = new AtomicInteger();
    assertEquals(
        TransactionCore.Outcome.UNCERTAIN,
        engine.commit(
            plan,
            inventory,
            () -> {
              deposits.incrementAndGet();
              throw new IllegalStateException("Provider may have credited before throwing");
            }));
    assertEquals(7, inventory.items);
    assertEquals(1, deposits.get());
    assertEquals(1, errors.size());
  }

  @Test
  void unknownAndNullResponsesRetainEscrowForReview() {
    Inventory first = new Inventory();
    assertEquals(
        TransactionCore.Outcome.UNCERTAIN, engine.commit(plan, first, () -> PaymentResult.UNKNOWN));
    Inventory second = new Inventory();
    assertEquals(TransactionCore.Outcome.UNCERTAIN, engine.commit(plan, second, () -> null));
    assertEquals(7, first.items);
    assertEquals(7, second.items);
  }

  @Test
  void failedCompensationIsQuarantined() {
    Inventory inventory = new Inventory();
    inventory.restorationFails = true;
    assertEquals(
        TransactionCore.Outcome.UNCERTAIN,
        engine.commit(plan, inventory, () -> PaymentResult.REJECTED));
    assertEquals(1, errors.size());
  }

  @Test
  void reentrantAndQuarantinedRequestsAreBlocked() {
    UUID player = UUID.randomUUID();
    PlayerTransactions transactions = new PlayerTransactions();
    assertTrue(transactions.acquire(player));
    assertFalse(transactions.acquire(player));
    transactions.release(player);
    assertTrue(transactions.acquire(player));
    transactions.quarantine(player);
    transactions.release(player);
    assertFalse(transactions.acquire(player));
  }

  @Test
  void emptyPlanCannotCallInventoryOrEconomy() {
    SellPlan empty = new SellPlan(List.of(), 0, BigDecimal.ZERO, BigDecimal.ONE);
    assertEquals(TransactionCore.Outcome.CHANGED, engine.commit(empty, null, null));
  }

  @Test
  void removalFailureNeverCallsEconomyAndRequestsReconciliation() {
    InventoryAccess broken =
        new InventoryAccess() {
          @Override
          public boolean unchanged(SellPlan ignored) {
            return true;
          }

          @Override
          public void remove(SellPlan ignored) {
            throw new IllegalStateException("Partial removal could not be verified");
          }

          @Override
          public void restore(SellPlan ignored) {
            throw new AssertionError("Core must not assume which items the adapter removed");
          }
        };
    assertEquals(
        TransactionCore.Outcome.UNCERTAIN,
        engine.commit(
            plan,
            broken,
            () -> {
              throw new AssertionError("Payment must not be attempted");
            }));
    assertEquals(1, errors.size());
  }

  @Test
  void brokenProviderLinkageDoesNotRestoreAfterPossibleCredit() {
    Inventory inventory = new Inventory();
    assertEquals(
        TransactionCore.Outcome.UNCERTAIN,
        engine.commit(
            plan,
            inventory,
            () -> {
              throw new NoSuchMethodError("Provider broke its runtime API");
            }));
    assertEquals(7, inventory.items);
  }
}
