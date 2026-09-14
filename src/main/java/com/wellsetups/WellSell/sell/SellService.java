package com.wellsetups.WellSell.sell;

import com.wellsetups.WellSell.command.PermissionId;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.economy.EconomyBridge;
import com.wellsetups.WellSell.economy.PaymentResult;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import com.wellsetups.WellSell.pricing.PriceUnavailableException;
import com.wellsetups.WellSell.pricing.SalePricing;
import com.wellsetups.WellSell.storage.JdbcHistory;
import com.wellsetups.WellSell.storage.SaleRecord;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

public final class SellService {
  private record Request(
      Player player,
      BukkitInventoryAccess inventory,
      Settings snapshot,
      PermissionId mode,
      EconomyBridge.Payment payment,
      SellPlan plan,
      SaleRecord record) {}

  private final Supplier<Settings> settings;
  private final PlatformExecutor platform;
  private final EconomyBridge economy;
  private final JdbcHistory storage;
  private final Messages messages;
  private final PlayerTransactions transactions;
  private final TransactionCore core;
  private final Logger logger;
  private final SalePricing pricing;
  private final SaleEvents events;
  private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
  private final AtomicLong nextPriceWarning = new AtomicLong();
  private volatile boolean closed;
  private Consumer<UUID> completed = player -> {};

  public SellService(
      Supplier<Settings> settings,
      PlatformExecutor platform,
      EconomyBridge economy,
      JdbcHistory storage,
      Messages messages,
      PlayerTransactions transactions,
      Logger logger,
      SalePricing pricing,
      SaleEvents events) {
    this.settings = settings;
    this.platform = platform;
    this.economy = economy;
    this.storage = storage;
    this.messages = messages;
    this.transactions = transactions;
    this.logger = logger;
    this.pricing = pricing;
    this.events = events;
    core =
        new TransactionCore(
            error ->
                logger.log(
                    Level.SEVERE, "Transaction boundary failure; reconcile audit record", error));
  }

  public void onCompleted(Consumer<UUID> callback) {
    completed = callback;
  }

  public SellPlan quote(Player player, BukkitInventoryAccess inventory) {
    platform.requireOwner(player);
    return pricing.quote(player, inventory, settings.get()).plan();
  }

  public SalePricing pricing() {
    return pricing;
  }

  public void priceUnavailable(Player player, PriceUnavailableException failure) {
    messages.send(player, "prices-unavailable");
    long now = System.currentTimeMillis();
    long next = nextPriceWarning.get();
    if (now >= next && nextPriceWarning.compareAndSet(next, now + 30_000)) {
      logger.log(Level.WARNING, "Selected shop cannot supply a safe price", failure);
    }
  }

  public void sell(Player player, BukkitInventoryAccess inventory, String source) {
    platform.requireOwner(player);
    Settings snapshot = settings.get();
    PermissionId mode = mode(source);
    if (!authorized(player, snapshot, mode) || !available(player)) {
      return;
    }
    UUID id = player.getUniqueId();
    if (!transactions.acquire(id)) {
      messages.send(
          player,
          transactions.quarantined(id) ? "uncertain" : "busy",
          Map.of("transaction", id.toString()));
      return;
    }
    boolean handedOff = false;
    try {
      Request request = prepare(player, inventory, source, snapshot, mode);
      if (request != null) {
        queue(request);
        handedOff = true;
      }
    } catch (PriceUnavailableException failure) {
      priceUnavailable(player, failure);
    } catch (IllegalArgumentException failure) {
      messages.send(player, "sale-too-large");
    } finally {
      if (!handedOff) {
        transactions.release(id);
      }
    }
  }

  private static PermissionId mode(String source) {
    return switch (source) {
      case "HAND" -> PermissionId.SELL_HAND;
      case "GUI" -> PermissionId.GUI;
      default -> PermissionId.SELL_INVENTORY;
    };
  }

  private boolean available(Player player) {
    if (!storage.ready()) {
      messages.send(player, "storage-unavailable");
      return false;
    }
    return true;
  }

  private Request prepare(
      Player player,
      BukkitInventoryAccess inventory,
      String source,
      Settings snapshot,
      PermissionId mode) {
    if (!pastCooldown(player, snapshot)) {
      return null;
    }
    EconomyBridge.Payment payment = economy.payment(player).orElse(null);
    if (payment == null) {
      messages.send(player, "economy-unavailable");
      return null;
    }
    SellPlan plan = quote(player, inventory);
    if (plan.empty()) {
      messages.send(player, "nothing-to-sell");
      return null;
    }
    if (!inventory.unchanged(plan)) {
      messages.send(player, "inventory-changed");
      return null;
    }
    SaleRecord record =
        SaleRecord.create(
            player.getUniqueId(), player.getName(), plan, source, inventory.recoveryItems(plan));
    if (!events.before(record)) {
      messages.send(player, "cancelled");
      return null;
    }
    if (!inventory.unchanged(plan)) {
      messages.send(player, "inventory-changed");
      return null;
    }
    cooldowns.put(player.getUniqueId(), System.nanoTime());
    messages.send(player, "processing");
    return new Request(player, inventory, snapshot, mode, payment, plan, record);
  }

  private boolean pastCooldown(Player player, Settings snapshot) {
    long wait = snapshot.general().integer("sell.cooldown-milliseconds", 0, 60_000) * 1_000_000L;
    Long previous = cooldowns.get(player.getUniqueId());
    if (previous != null && System.nanoTime() - previous < wait) {
      messages.send(player, "cooldown");
      return false;
    }
    return true;
  }

  private void queue(Request request) {
    storage
        .prepare(request.record())
        .whenComplete(
            (ignored, failure) -> {
              if (failure != null) {
                transactions.release(request.record().player());
                storage.logFailure("Cannot prepare sale " + request.record().id(), failure);
                platform.player(
                    request.player(),
                    () -> messages.send(request.player(), "storage-unavailable"),
                    () -> {});
              } else {
                schedule(request);
              }
            });
  }

  private void schedule(Request request) {
    AtomicBoolean claimed = new AtomicBoolean();
    Runnable retired =
        () -> {
          if (claimed.compareAndSet(false, true)) {
            finish(request.record(), "CANCELLED");
          }
        };
    try {
      platform.player(
          request.player(),
          () -> {
            if (claimed.compareAndSet(false, true)) {
              commit(request);
            }
          },
          retired);
    } catch (RuntimeException failure) {
      retired.run();
      logger.log(Level.WARNING, "Could not schedule prepared sale", failure);
    }
  }

  private boolean authorized(Player player, Settings snapshot, PermissionId mode) {
    if (closed) {
      messages.send(player, "disabled");
      return false;
    }
    if (!snapshot.permissions().has(player, PermissionId.SELL)
        || !snapshot.permissions().has(player, mode)) {
      messages.send(player, "no-permission");
      return false;
    }
    boolean enabled =
        mode == PermissionId.GUI
            ? snapshot.gui().enabled()
            : snapshot
                .general()
                .flag(mode == PermissionId.SELL_HAND ? "sell.allow-hand" : "sell.allow-inventory");
    if (!enabled) {
      messages.send(player, "disabled");
    }
    return enabled;
  }

  private boolean current(Request request) {
    return !closed
        && !transactions.quarantined(request.record().player())
        && settings.get() == request.snapshot()
        && authorized(request.player(), request.snapshot(), request.mode())
        && request.payment().available()
        && request.inventory().unchanged(request.plan());
  }

  private void commit(Request request) {
    String state = "CANCELLED";
    try {
      if (!current(request)) {
        messages.send(request.player(), "cancelled");
        return;
      }
      SalePricing.PricedSale latest =
          pricing.quote(request.player(), request.inventory(), request.snapshot());
      if (!request.plan().sameQuote(latest.plan())) {
        messages.send(request.player(), "price-changed");
        return;
      }
      TransactionCore.Outcome outcome =
          core.commit(
              request.plan(),
              request.inventory(),
              () -> {
                PaymentResult result = request.payment().deposit(request.plan().money());
                if (result == PaymentResult.SUCCESS) {
                  latest.afterPayment().run();
                }
                return result;
              });
      state = outcome.name();
      notifyOutcome(request, outcome);
    } catch (PriceUnavailableException failure) {
      priceUnavailable(request.player(), failure);
    } finally {
      finish(request.record(), state);
    }
  }

  private void notifyOutcome(Request request, TransactionCore.Outcome outcome) {
    if (outcome == TransactionCore.Outcome.UNCERTAIN) {
      transactions.quarantine(request.record().player());
      logger.severe(
          "UNCERTAIN sale "
              + request.record().id()
              + " for "
              + request.record().player()
              + "; inspect its audit file before any refund.");
    }
    Map<String, String> values = new HashMap<>(messages.sale(request.plan()));
    values.put("transaction", request.record().id().toString());
    String key =
        switch (outcome) {
          case SUCCESS -> "sold";
          case CHANGED -> "inventory-changed";
          case REJECTED -> "payment-rejected";
          case UNCERTAIN -> "uncertain";
        };
    messages.send(request.player(), key, values);
    if (outcome == TransactionCore.Outcome.SUCCESS) {
      request.snapshot().success().play(request.player());
    } else {
      request.snapshot().error().play(request.player());
    }
  }

  private void finish(SaleRecord record, String state) {
    storage
        .finish(record, state)
        .whenComplete(
            (ignored, failure) -> {
              try {
                if (failure != null) {
                  transactions.quarantine(record.player());
                  storage.logFailure(
                      "Could not persist "
                          + state
                          + " sale "
                          + record.id()
                          + "; payment will NOT be repeated",
                      failure);
                } else if (state.equals("SUCCESS")) {
                  completed.accept(record.player());
                  events.finished(record, state, null);
                }
              } catch (RuntimeException failureInCache) {
                logger.log(
                    Level.WARNING, "Sale committed but cache refresh failed", failureInCache);
              } finally {
                transactions.release(record.player());
              }
            });
  }

  public void forget(UUID player) {
    cooldowns.remove(player);
  }

  public boolean pauseForMigration() {
    return transactions.pause();
  }

  public void resumeAfterMigration() {
    transactions.resume();
  }

  public void close() {
    closed = true;
  }
}
