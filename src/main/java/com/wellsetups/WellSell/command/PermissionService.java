package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

public final class PermissionService {
  public record Node(String name, PermissionDefault defaultValue) {}

  private final Map<PermissionId, Node> nodes;

  public PermissionService(ConfigTree config) {
    Map<PermissionId, Node> parsed = new EnumMap<>(PermissionId.class);
    Set<String> used = new HashSet<>();
    for (PermissionId id : PermissionId.values()) {
      String path = "permissions." + id.key();
      String node = config.text(path + ".node");
      PermissionDefault defaults =
          PermissionDefault.getByName(String.valueOf(config.get(path + ".default")));
      if (!node.matches("[a-z0-9][a-z0-9_.-]{0,127}") || defaults == null || !used.add(node)) {
        throw new IllegalArgumentException(
            "Invalid or duplicate permission configuration at " + path);
      }
      parsed.put(id, new Node(node, defaults));
    }
    nodes = Map.copyOf(parsed);
  }

  public boolean has(CommandSender sender, PermissionId id) {
    return sender.hasPermission(node(id));
  }

  public String node(PermissionId id) {
    return nodes.get(id).name();
  }

  public void register(PluginManager manager, Set<Permission> owned) {
    for (Node node : nodes.values()) {
      Permission existing = manager.getPermission(node.name());
      if (existing == null) {
        Permission permission = new Permission(node.name(), node.defaultValue());
        manager.addPermission(permission);
        owned.add(permission);
      } else if (owned.contains(existing)) {
        existing.setDefault(node.defaultValue());
      }
    }
  }
}
