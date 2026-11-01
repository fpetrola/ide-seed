package ide.cli;

import ide.core.Json;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An answer as a person reads it: text as itself, a list one item per line, an object one key per line, with what a
 * key holds over several lines indented below it.
 */
final class Text {

    private Text() {
    }

    static String of(String json) {
        var out = new StringBuilder();
        switch (Json.read(json)) {
            case null -> {
            }
            case List<?> list -> list.forEach(item -> out.append(inline(item)).append('\n'));
            case Map<?, ?> map -> map.forEach((key, value) -> entry(String.valueOf(key), value, out));
            case Object value -> out.append(value).append(String.valueOf(value).endsWith("\n") ? "" : "\n");
        }
        return out.toString();
    }

    private static void entry(String key, Object value, StringBuilder out) {
        var lines = switch (value) {
            case List<?> list -> list.stream().map(Text::inline).toList();
            case String text when text.contains("\n") -> text.lines().toList();
            case null, default -> null;
        };
        if (lines == null) {
            out.append(key).append(": ").append(inline(value)).append('\n');
        } else if (lines.isEmpty()) {
            out.append(key).append(": none\n");
        } else {
            out.append(key).append(":\n");
            lines.forEach(line -> out.append("  ").append(line).append('\n'));
        }
    }

    /** A value on one line: an object as its entries, a list as its items. */
    private static String inline(Object value) {
        return switch (value) {
            case Map<?, ?> map -> map.entrySet().stream().map(e -> e.getKey() + ": " + inline(e.getValue()))
                    .collect(Collectors.joining(", "));
            case List<?> list -> list.stream().map(Text::inline).collect(Collectors.joining(", "));
            case null -> "null";
            default -> String.valueOf(value);
        };
    }
}
