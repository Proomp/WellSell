package com.wellsetups.WellSell.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocaleFilesTest {
  @Test
  void allShippedLocalesHaveTheAuthoritativeKeysAndVersion() throws Exception {
    var resource = getClass().getClassLoader().getResource("lang");
    assertNotNull(resource);
    Path folder = Path.of(resource.toURI());
    ConfigTree english =
        new ConfigTree(ConfigFiles.parse(Files.readString(folder.resolve("en_US.yml"))));
    try (var paths = Files.list(folder)) {
      for (Path file : paths.filter(path -> path.toString().endsWith(".yml")).toList()) {
        ConfigTree translated = new ConfigTree(ConfigFiles.parse(Files.readString(file)));
        assertEquals(
            english.section("messages").keySet(),
            translated.section("messages").keySet(),
            "Missing or unknown locale keys in " + file.getFileName());
        assertEquals(english.get("meta.version"), translated.get("meta.version"), file.toString());
        assertEquals(
            file.getFileName().toString().replace(".yml", ""), translated.text("meta.locale"));
        for (Map.Entry<String, Object> message : translated.section("messages").entrySet()) {
          assertEquals(String.class, message.getValue().getClass(), file + ": " + message.getKey());
        }
      }
    }
  }
}
