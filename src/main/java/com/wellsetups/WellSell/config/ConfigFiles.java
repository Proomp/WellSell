package com.wellsetups.WellSell.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class ConfigFiles {
  private final Path directory;
  private final ClassLoader resources;

  public ConfigFiles(Path directory, ClassLoader resources) {
    this.directory = directory;
    this.resources = resources;
  }

  public ConfigTree load(String name, Set<String> openMaps) throws IOException {
    Path file = directory.resolve(name);
    Files.createDirectories(file.getParent());
    Map<String, Object> defaults;
    try (InputStream input = resources.getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("Missing bundled configuration: " + name);
      }
      byte[] bytes = input.readAllBytes();
      defaults = parse(new String(bytes, StandardCharsets.UTF_8));
      if (!Files.exists(file)) {
        Files.write(file, bytes);
      }
    }
    Map<String, Object> custom = read(file);
    Object version = custom.getOrDefault("config-version", 1);
    if (!version.toString().equals("1")) {
      throw new IOException("Unsupported config-version in " + name + ": " + version);
    }
    Map<String, Object> merged = DefaultMerger.merge(custom, defaults, openMaps);
    // Preserve comments and formatting in the original. A companion file exposes newly
    // defaulted keys without rewriting administrator YAML or dynamic price/group maps.
    if (!merged.equals(custom)) {
      Path backup = file.resolveSibling(file.getFileName() + ".v1.bak");
      if (!Files.exists(backup)) {
        Files.copy(file, backup);
      }
      Path companion = file.resolveSibling(file.getFileName() + ".merged.yml");
      Path temporary = Files.createTempFile(file.getParent(), "wellsell-", ".tmp");
      Files.writeString(temporary, new Yaml().dump(merged), StandardCharsets.UTF_8);
      Files.move(temporary, companion, StandardCopyOption.REPLACE_EXISTING);
    }
    return new ConfigTree(merged);
  }

  public ConfigTree locale(String locale) throws IOException {
    if (!locale.matches("[a-zA-Z0-9_-]{2,32}")) {
      throw new IllegalArgumentException("Invalid locale file name: " + locale);
    }
    Path file = directory.resolve("lang/" + locale + ".yml");
    return new ConfigTree(Files.exists(file) ? read(file) : Map.of());
  }

  public ConfigTree bundled(String name) throws IOException {
    try (InputStream input = resources.getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("Missing bundled resource " + name);
      }
      return new ConfigTree(parse(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
    }
  }

  private static Map<String, Object> read(Path file) throws IOException {
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Object value = yaml().load(reader);
      return mapping(value);
    }
  }

  public static Map<String, Object> parse(String text) {
    return mapping(yaml().load(text));
  }

  private static Map<String, Object> mapping(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Configuration must be a YAML mapping");
    }
    return DefaultMerger.stringMap(map);
  }

  private static Yaml yaml() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(20);
    options.setCodePointLimit(2_000_000);
    return new Yaml(new SafeConstructor(options));
  }
}
