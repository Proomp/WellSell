package com.wellsetups.WellSell.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.wellsetups.WellSell.config.ConfigFiles;
import com.wellsetups.WellSell.config.ConfigTree;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PriceImportStoreTest {
  @TempDir Path directory;

  @Test
  void importMergesRatesAndRetainsTheExactOriginalBackup() throws Exception {
    String original = "# My prices\nconfig-version: 1\nprices:\n  STONE: '0.25'\n  DIAMOND: '2'\n";
    Files.writeString(directory.resolve("prices.yml"), original);
    PriceImportStore store = new PriceImportStore(directory);
    String backup = store.save(store.snapshot(), Map.of("DIAMOND", new BigDecimal("3.75")));
    assertEquals(original, Files.readString(directory.resolve(backup)));
    var actual =
        new ConfigTree(ConfigFiles.parse(Files.readString(directory.resolve("prices.yml"))));
    assertEquals(new BigDecimal("0.25"), actual.decimal("prices.STONE"));
    assertEquals(new BigDecimal("3.75"), actual.decimal("prices.DIAMOND"));
  }

  @Test
  void concurrentAdministratorEditsAreNotOverwritten() throws Exception {
    Path file = directory.resolve("prices.yml");
    Files.writeString(file, "config-version: 1\nprices: {}\n");
    PriceImportStore store = new PriceImportStore(directory);
    byte[] before = store.snapshot();
    String edited = "# A new administrator edit\nconfig-version: 1\nprices: {}\n";
    Files.writeString(file, edited);
    assertThrows(IOException.class, () -> store.save(before, Map.of("STONE", BigDecimal.ONE)));
    assertEquals(edited, Files.readString(file));
  }

  @Test
  void emptyOrInvalidImportDoesNotRewriteTheFile() throws Exception {
    Path file = directory.resolve("prices.yml");
    String original = "config-version: 1\nprices: {}\n";
    Files.writeString(file, original);
    PriceImportStore store = new PriceImportStore(directory);
    byte[] before = store.snapshot();
    assertThrows(IllegalArgumentException.class, () -> store.save(before, Map.of()));
    assertThrows(
        IllegalArgumentException.class, () -> store.save(before, Map.of("STONE", BigDecimal.ZERO)));
    assertEquals(original, Files.readString(file));
  }
}
