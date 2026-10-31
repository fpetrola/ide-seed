package ide.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Minimal JSON writer for maps, lists, strings, numbers, booleans and null. */
public final class Json {

    private Json() {
    }

    public static String write(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> quote(s);
            case Number n -> n.toString();
            case Boolean b -> b.toString();
            case Map<?, ?> map -> map.entrySet().stream()
                    .map(e -> quote(String.valueOf(e.getKey())) + ":" + write(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case List<?> list -> list.stream().map(Json::write).collect(Collectors.joining(",", "[", "]"));
            default -> quote(value.toString());
        };
    }

    private static String quote(String s) {
        var out = new StringBuilder("\"");
        for (var c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
