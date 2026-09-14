package com.wellsetups.WellSell.storage;

import com.wellsetups.WellSell.api.CategoryContribution;
import com.wellsetups.WellSell.api.SaleSummary;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class SummaryCodec {
  private SummaryCodec() {}

  static String encode(SaleSummary summary) {
    Properties data = new Properties();
    data.setProperty("provider", summary.provider());
    data.setProperty("containers", Integer.toString(summary.containers()));
    data.setProperty(
        "categories", String.join(",", new java.util.TreeSet<>(summary.categories().keySet())));
    summary
        .categories()
        .forEach(
            (id, value) -> {
              data.setProperty(id + ".earned", value.earned().toPlainString());
              data.setProperty(id + ".items", Long.toString(value.items()));
              data.setProperty(id + ".permission", value.permissionMultiplier().toPlainString());
              data.setProperty(id + ".progression", value.progressionMultiplier().toPlainString());
              data.setProperty(id + ".combined", value.combinedMultiplier().toPlainString());
            });
    try {
      StringWriter out = new StringWriter();
      data.store(out, "WellSell sale summary v1");
      return out.toString();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  static SaleSummary decode(String text) {
    if (text == null || text.isEmpty()) {
      return SaleSummary.empty();
    }
    if (text.length() > 100_000) {
      throw new IllegalArgumentException("Oversized sale summary");
    }
    Properties data = new Properties();
    try {
      data.load(new StringReader(text));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    Map<String, CategoryContribution> categories = new LinkedHashMap<>();
    String names = data.getProperty("categories", "");
    if (!names.isEmpty()) {
      for (String id : names.split(",")) {
        categories.put(
            id,
            new CategoryContribution(
                new BigDecimal(data.getProperty(id + ".earned")),
                Long.parseLong(data.getProperty(id + ".items")),
                new BigDecimal(data.getProperty(id + ".permission")),
                new BigDecimal(data.getProperty(id + ".progression")),
                new BigDecimal(data.getProperty(id + ".combined"))));
      }
    }
    return new SaleSummary(
        data.getProperty("provider"),
        categories,
        Integer.parseInt(data.getProperty("containers", "0")));
  }
}
