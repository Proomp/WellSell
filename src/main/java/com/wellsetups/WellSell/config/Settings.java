package com.wellsetups.WellSell.config;

import com.wellsetups.WellSell.command.CommandNames;
import com.wellsetups.WellSell.command.PermissionService;
import com.wellsetups.WellSell.gui.GuiSettings;
import com.wellsetups.WellSell.message.LocaleCatalog;
import com.wellsetups.WellSell.message.SoundEffect;
import com.wellsetups.WellSell.pricing.ConfiguredPrices;
import com.wellsetups.WellSell.pricing.MoneyPolicy;
import com.wellsetups.WellSell.pricing.Multipliers;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record Settings(
    ConfigTree general,
    ConfigTree storage,
    ConfigTree integrations,
    CommandNames commands,
    PermissionService permissions,
    ConfiguredPrices prices,
    MoneyPolicy money,
    Multipliers multipliers,
    LocaleCatalog locale,
    GuiSettings gui,
    DateTimeFormatter dateFormat,
    SoundEffect success,
    SoundEffect error,
    ExpansionSettings expansion) {
  public String formatMoney(java.math.BigDecimal value) {
    DecimalFormat format =
        new DecimalFormat(
            general.text("money.pattern"),
            DecimalFormatSymbols.getInstance(Locale.forLanguageTag(general.text("money.locale"))));
    format.setRoundingMode(money.rounding());
    return general.text("money.prefix") + format.format(value) + general.text("money.suffix");
  }
}
