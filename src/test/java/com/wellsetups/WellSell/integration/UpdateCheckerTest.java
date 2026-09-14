package com.wellsetups.WellSell.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
  @Test
  void comparesVersionsNumericallyAndIgnoresPrereleases() {
    assertTrue(UpdateChecker.newer("1.10.0", "1.9.99"));
    assertTrue(UpdateChecker.newer("2.0.0", "1.99.99"));
    assertFalse(UpdateChecker.newer("1.0.0", "1.0.0"));
    assertFalse(UpdateChecker.newer("1.0.0", "2.0.0"));
    assertFalse(UpdateChecker.newer("2.0.0-beta", "1.0.0"));
    assertFalse(UpdateChecker.newer("garbage", "1.0.0"));
  }

  @Test
  void rejectsInsecureAndCredentialBearingEndpointsBeforeNetworkAccess() {
    assertThrows(
        IllegalArgumentException.class, () -> UpdateChecker.fetch("http://example.invalid/latest"));
    assertThrows(
        IllegalArgumentException.class,
        () -> UpdateChecker.fetch("https://user:pass@example.invalid/latest"));
  }
}
