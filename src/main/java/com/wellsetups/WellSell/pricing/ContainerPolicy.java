package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.config.ConfigTree;

public record ContainerPolicy(Rule shulker, Rule bundle, int maximumDepth, int maximumComponents) {
  public record Rule(
      boolean enabled,
      boolean sellContents,
      boolean includeContainer,
      boolean nested,
      boolean metadata) {}

  public static ContainerPolicy load(ConfigTree config) {
    return new ContainerPolicy(
        rule(config, "shulker-box"),
        rule(config, "bundles"),
        config.integer("maximum-depth", 1, 3),
        config.integer("maximum-components", 36, 4096));
  }

  private static Rule rule(ConfigTree config, String path) {
    return new Rule(
        config.flag(path + ".enabled"),
        config.flag(path + ".sell-contents"),
        config.flag(path + ".include-container-value"),
        config.flag(path + ".allow-nested-containers"),
        config.flag(path + ".allow-metadata"));
  }
}
