package com.wellsetups.WellSell.integration;

import com.wellsetups.WellSell.command.PermissionId;
import com.wellsetups.WellSell.config.ConfigTree;
import com.wellsetups.WellSell.config.Settings;
import com.wellsetups.WellSell.message.Messages;
import com.wellsetups.WellSell.platform.PlatformExecutor;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public final class UpdateChecker implements Listener {
  private final Plugin plugin;
  private final PlatformExecutor platform;
  private final Supplier<Settings> settings;
  private final Messages messages;
  private final ConfigTree config;
  private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
  private volatile String latest;

  public UpdateChecker(
      Plugin plugin,
      PlatformExecutor platform,
      Supplier<Settings> settings,
      Messages messages,
      ConfigTree config) {
    this.plugin = plugin;
    this.platform = platform;
    this.settings = settings;
    this.messages = messages;
    this.config = config;
  }

  public void start() {
    if (!config.flag("update-checker.enabled")) {
      return;
    }
    String address = config.text("update-checker.version-url");
    String current = plugin.getDescription().getVersion();
    platform
        .async(() -> fetch(address))
        .whenComplete(
            (version, failure) -> {
              if (failure != null) {
                plugin
                    .getLogger()
                    .log(
                        Level.WARNING,
                        "Optional update check failed; normal operation is unaffected",
                        failure);
              } else if (newer(version, current)) {
                latest = version;
                if (config.flag("update-checker.notify-console")) {
                  platform.global(
                      () ->
                          messages.send(
                              plugin.getServer().getConsoleSender(),
                              "update",
                              Map.of("latest", version)));
                }
              }
            });
  }

  static String fetch(String address) {
    URI uri = URI.create(address);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("Update URL must be HTTPS without embedded credentials");
    }
    try {
      HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(5000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "WellSell-update-checker");
      try {
        if (connection.getResponseCode() != 200) {
          throw new IOException("Update endpoint returned HTTP " + connection.getResponseCode());
        }
        try (InputStream input = connection.getInputStream()) {
          byte[] bytes = input.readNBytes(129);
          if (bytes.length > 128) {
            throw new IOException("Update version response exceeds 128 bytes");
          }
          String version = new String(bytes, StandardCharsets.UTF_8).trim();
          if (!version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
            throw new IOException(
                "Update endpoint must contain a stable major.minor.patch version");
          }
          return version;
        }
      } finally {
        connection.disconnect();
      }
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  public static boolean newer(String candidate, String current) {
    if (!candidate.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
        || !current.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
      return false;
    }
    String[] left = candidate.split("\\.");
    String[] right = current.split("\\.");
    for (int index = 0; index < 3; index++) {
      int compare = Integer.compare(Integer.parseInt(left[index]), Integer.parseInt(right[index]));
      if (compare != 0) {
        return compare > 0;
      }
    }
    return false;
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    String version = latest;
    if (version != null
        && config.flag("update-checker.notify-admins")
        && settings.get().permissions().has(event.getPlayer(), PermissionId.ADMIN)
        && notified.add(event.getPlayer().getUniqueId())) {
      messages.send(event.getPlayer(), "update", Map.of("latest", version));
    }
  }
}
