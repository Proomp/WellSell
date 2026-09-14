package com.wellsetups.WellSell.api;

import java.util.List;

public record CategoryDefinition(
    String id, String displayName, String icon, List<String> identities) {
  public CategoryDefinition {
    identities = List.copyOf(identities);
  }
}
