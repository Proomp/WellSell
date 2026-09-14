package com.wellsetups.WellSell.message;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TextFormatter {
  private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");
  private static final Pattern LEGACY = Pattern.compile("(?i)&(#(?:[0-9a-f]{6})|[0-9a-fk-or])");
  private static final String[] LEGACY_TAGS = {
    "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
    "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
  };
  private final MiniMessage mini = MiniMessage.miniMessage();
  private final LegacyComponentSerializer legacy =
      LegacyComponentSerializer.builder()
          .character('§')
          .hexColors()
          .useUnusualXRepeatedCharacterHexFormat()
          .build();

  public String format(String template, String prefix, Map<String, String> values) {
    String expanded = template.replace("%prefix%", prefix).replace("<prefix>", prefix);
    TagResolver.Builder resolver = TagResolver.builder();
    Matcher matcher = PLACEHOLDER.matcher(expanded);
    StringBuffer safe = new StringBuffer();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = values.get(key);
      if (value == null) {
        matcher.appendReplacement(safe, Matcher.quoteReplacement(matcher.group()));
      } else {
        String tag = "ws_" + key;
        resolver.resolver(Placeholder.unparsed(tag, value));
        matcher.appendReplacement(safe, Matcher.quoteReplacement("<" + tag + ">"));
      }
    }
    matcher.appendTail(safe);
    Component component = mini.deserialize(legacyTags(safe.toString()), resolver.build());
    return legacy.serialize(component);
  }

  private static String legacyTags(String input) {
    Matcher matcher = LEGACY.matcher(input);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String code = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
      String tag;
      if (code.startsWith("#")) {
        tag = "<" + code + ">";
      } else {
        int color = Character.digit(code.charAt(0), 16);
        tag =
            color >= 0
                ? "<" + LEGACY_TAGS[color] + ">"
                : switch (code) {
                  case "k" -> "<obfuscated>";
                  case "l" -> "<bold>";
                  case "m" -> "<strikethrough>";
                  case "n" -> "<underlined>";
                  case "o" -> "<italic>";
                  default -> "<reset>";
                };
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(tag));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
