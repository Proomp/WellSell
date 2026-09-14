package com.wellsetups.WellSell.api;

import java.math.BigDecimal;

/**
 * Money and category multipliers captured by one quote. A mixed-category multiplier is weighted.
 */
public record Valuation(int amount, BigDecimal money, BigDecimal multiplier, SaleSummary summary) {}
