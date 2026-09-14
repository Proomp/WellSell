package com.wellsetups.WellSell.platform;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.plugin.Plugin;

/** Legacy Spigot's missing Server#getCommandMap is isolated here, at startup only. */
public final class CommandRegistration implements AutoCloseable {
  private final Plugin plugin;
  private final CommandMap commands;
  private final List<Command> owned = new ArrayList<>();

  public CommandRegistration(Plugin plugin) {
    this.plugin = plugin;
    try {
      commands =
          (CommandMap)
              plugin.getServer().getClass().getMethod("getCommandMap").invoke(plugin.getServer());
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
      throw new IllegalStateException(
          "Server does not expose getCommandMap; dynamic roots cannot be registered safely",
          failure);
    }
  }

  public void register(Command command) {
    if (!commands.register("wellsell", command)) {
      plugin
          .getLogger()
          .warning(
              "Command /"
                  + command.getName()
                  + " is already taken; use /wellsell:"
                  + command.getName());
    }
    owned.add(command);
  }

  @Override
  public void close() {
    // Bukkit clears mappings during normal server shutdown. Live plugin unload/reload is
    // unsupported: Command#unregister does not safely remove every known alias mapping.
    owned.forEach(command -> command.unregister(commands));
  }
}
