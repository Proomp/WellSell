package com.wellsetups.WellSell.economy;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.entity.Player;

public interface EconomyBridge {
  interface Payment {
    boolean available();

    PaymentResult deposit(BigDecimal money);
  }

  Optional<Payment> payment(Player player);

  String name();

  static EconomyBridge unavailable() {
    return new EconomyBridge() {
      @Override
      public Optional<Payment> payment(Player player) {
        return Optional.empty();
      }

      @Override
      public String name() {
        return "—";
      }
    };
  }
}
