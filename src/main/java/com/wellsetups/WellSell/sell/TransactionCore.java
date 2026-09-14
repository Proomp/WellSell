package com.wellsetups.WellSell.sell;

import com.wellsetups.WellSell.economy.PaymentResult;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TransactionCore {
  public enum Outcome {
    SUCCESS,
    CHANGED,
    REJECTED,
    UNCERTAIN
  }

  private final Consumer<RuntimeException> diagnostic;

  public TransactionCore(Consumer<RuntimeException> diagnostic) {
    this.diagnostic = diagnostic;
  }

  public Outcome commit(SellPlan plan, InventoryAccess inventory, Supplier<PaymentResult> deposit) {
    if (plan.empty() || !inventory.unchanged(plan)) {
      return Outcome.CHANGED;
    }
    try {
      inventory.remove(plan);
    } catch (RuntimeException | LinkageError failure) {
      diagnostic.accept(new IllegalStateException("Inventory removal boundary failed", failure));
      // Inventory adapters compensate partial removals. An adapter failure is still
      // quarantined because blindly retrying would assume compensation succeeded.
      return Outcome.UNCERTAIN;
    }
    PaymentResult result;
    try {
      result = deposit.get();
    } catch (RuntimeException | LinkageError failure) {
      diagnostic.accept(new IllegalStateException("Economy boundary failed", failure));
      return Outcome.UNCERTAIN;
    }
    if (result == PaymentResult.SUCCESS) {
      return Outcome.SUCCESS;
    }
    if (result != PaymentResult.REJECTED) {
      return Outcome.UNCERTAIN;
    }
    try {
      inventory.restore(plan);
      return Outcome.REJECTED;
    } catch (RuntimeException | LinkageError failure) {
      diagnostic.accept(
          new IllegalStateException("Inventory compensation boundary failed", failure));
      return Outcome.UNCERTAIN;
    }
  }
}
