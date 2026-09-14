package com.wellsetups.WellSell.pricing;

/** A selected shop cannot safely provide a Vault quote; never fall back to local rates. */
public final class PriceUnavailableException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public PriceUnavailableException(String message) {
    super(message);
  }

  public PriceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
