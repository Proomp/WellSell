package com.wellsetups.WellSell.pricing;

import java.util.Locale;

public enum PriceSource {
  CONFIG,
  ECONOMYSHOPGUI,
  SHOPGUIPLUS;

  public static PriceSource parse(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
