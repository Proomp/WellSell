package com.wellsetups.WellSell.pricing;

import com.wellsetups.WellSell.config.ConfigFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Only called from a background worker. Explicit imports retain the original as a backup. */
public final class PriceImportStore {
  private final Path file;

  public PriceImportStore(Path folder) {
    file = folder.resolve("prices.yml");
  }

  public byte[] snapshot() throws IOException {
    return Files.readAllBytes(file);
  }

  public String save(byte[] original, Map<String, BigDecimal> imported) throws IOException {
    if (imported.isEmpty()) {
      throw new IllegalArgumentException("No eligible material prices to import");
    }
    if (!Arrays.equals(original, Files.readAllBytes(file))) {
      throw new IOException("prices.yml changed during import; refusing to overwrite it");
    }
    Map<String, Object> config =
        new LinkedHashMap<>(ConfigFiles.parse(new String(original, StandardCharsets.UTF_8)));
    Map<String, Object> prices = new TreeMap<>();
    Object existing = config.get("prices");
    if (existing instanceof Map<?, ?> values) {
      values.forEach((key, value) -> prices.put(key.toString(), value));
    }
    imported.forEach(
        (key, value) -> {
          if (!key.matches("[A-Z0-9_]+")
              || value.signum() <= 0
              || value.compareTo(MoneyPolicy.HARD_MAXIMUM) > 0) {
            throw new IllegalArgumentException("Invalid imported material price");
          }
          prices.put(key, value.stripTrailingZeros().toPlainString());
        });
    config.put("prices", prices);
    String backupName =
        "prices.yml.import-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".bak";
    Files.write(file.resolveSibling(backupName), original);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    Path temporary = Files.createTempFile(file.getParent(), "price-import-", ".tmp");
    try {
      Files.writeString(
          temporary,
          "# Imported base prices. Original formatting/comments: "
              + backupName
              + "\n"
              + new Yaml(options).dump(config),
          StandardCharsets.UTF_8);
      if (!Arrays.equals(original, Files.readAllBytes(file))) {
        throw new IOException("prices.yml changed before import commit");
      }
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return backupName;
  }
}
