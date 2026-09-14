package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.economy.EconomyBridge;
import com.wellsetups.WellSell.economy.PaymentResult;
import java.math.BigDecimal;
import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/** Loaded only when Vault is enabled; no Vault types escape this boundary. */
public final class VaultEconomy implements EconomyBridge {
  private final ServicesManager services;

  public VaultEconomy(ServicesManager services) {
    this.services = services;
  }

  @Override
  public Optional<Payment> payment(Player player) {
    RegisteredServiceProvider<Economy> registration = services.getRegistration(Economy.class);
    if (registration == null
        || !registration.getPlugin().isEnabled()
        || !registration.getProvider().isEnabled()) {
      return Optional.empty();
    }
    Economy economy = registration.getProvider();
    return Optional.of(
        new Payment() {
          @Override
          public boolean available() {
            RegisteredServiceProvider<Economy> current = services.getRegistration(Economy.class);
            return current != null
                && current.getProvider() == economy
                && current.getPlugin().isEnabled()
                && economy.isEnabled();
          }

          @Override
          public PaymentResult deposit(BigDecimal money) {
            int digits = economy.fractionalDigits();
            if (!available() || digits >= 0 && money.stripTrailingZeros().scale() > digits) {
              return PaymentResult.REJECTED;
            }
            double amount = money.doubleValue();
            if (!Double.isFinite(amount)
                || amount <= 0
                || BigDecimal.valueOf(amount).compareTo(money) != 0) {
              return PaymentResult.REJECTED;
            }
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (response == null
                || !Double.isFinite(response.amount)
                || !Double.isFinite(response.balance)) {
              return PaymentResult.UNKNOWN;
            }
            if (response.transactionSuccess()) {
              return BigDecimal.valueOf(response.amount).compareTo(money) == 0
                  ? PaymentResult.SUCCESS
                  : PaymentResult.UNKNOWN;
            }
            // Failure plus a reported nonzero movement is contradictory; never restore blindly.
            return response.amount == 0 ? PaymentResult.REJECTED : PaymentResult.UNKNOWN;
          }
        });
  }

  @Override
  public String name() {
    RegisteredServiceProvider<Economy> registration = services.getRegistration(Economy.class);
    return registration == null ? "—" : "Vault / " + registration.getProvider().getName();
  }
}
