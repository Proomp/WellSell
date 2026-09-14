package com.wellsetups.WellSell.command;

import com.wellsetups.WellSell.config.ConfigTree;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record CommandNames(Map<CommandId, Definition> commands) {
  public record Definition(String name, List<String> aliases) {
    public Definition {
      aliases = List.copyOf(aliases);
    }
  }

  public CommandNames {
    commands = Map.copyOf(commands);
    Set<String> seen = new HashSet<>();
    for (CommandId id : CommandId.values()) {
      Definition definition = commands.get(id);
      if (definition == null) {
        throw new IllegalArgumentException("Missing command: " + id.key());
      }
      List<String> labels = new ArrayList<>(definition.aliases());
      labels.add(definition.name());
      for (String label : labels) {
        if (!label.matches("[a-z0-9][a-z0-9_-]{0,31}") || !seen.add(label)) {
          throw new IllegalArgumentException("Invalid or duplicate command label: " + label);
        }
      }
    }
  }

  public static CommandNames load(ConfigTree tree) {
    Map<CommandId, Definition> map = new EnumMap<>(CommandId.class);
    for (CommandId id : CommandId.values()) {
      String path = "commands." + id.key();
      map.put(id, new Definition(tree.text(path + ".name"), tree.strings(path + ".aliases")));
    }
    return new CommandNames(map);
  }

  public String name(CommandId id) {
    return commands.get(id).name();
  }
}
