package ide.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Minimal JSON for maps, lists, strings, numbers, booleans and null, both ways. Read, an object is a map in the order
 * it was written, a whole number a {@code Long}, any other number a {@code Double}.
 */
public final class Json {

    private Json() {
    }

    public static Object read(String text) {
        var reader = new Reader(text);
        var value = reader.value();
        reader.spaces();
        if (reader.at < text.length()) throw reader.error("the end");
        return value;
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

    /** One value, read left to right. */
    private static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            spaces();
            if (at >= text.length()) throw error("a value");
            return switch (text.charAt(at)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true", true);
                case 'f' -> word("false", false);
                case 'n' -> word("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            var object = new LinkedHashMap<String, Object>();
            at++;
            if (next('}')) return object;
            do {
                spaces();
                var key = string();
                expect(':');
                object.put(key, value());
            } while (next(','));
            expect('}');
            return object;
        }

        private List<Object> array() {
            var array = new ArrayList<Object>();
            at++;
            if (next(']')) return array;
            do array.add(value());
            while (next(','));
            expect(']');
            return array;
        }

        private String string() {
            if (at >= text.length() || text.charAt(at) != '"') throw error("a string");
            var out = new StringBuilder();
            for (at++; at < text.length() && text.charAt(at) != '"'; at++) {
                var c = text.charAt(at);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                var escaped = text.charAt(++at);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at + 1, at + 5), 16));
                        at += 4;
                    }
                    default -> out.append(escaped);
                }
            }
            if (at >= text.length()) throw error("'\"'");
            at++;
            return out.toString();
        }

        private Object word(String word, Object value) {
            if (!text.startsWith(word, at)) throw error("a value");
            at += word.length();
            return value;
        }

        private Number number() {
            var start = at;
            while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            var number = text.substring(start, at);
            if (number.isEmpty()) throw error("a value");
            return number.contains(".") || number.contains("e") || number.contains("E")
                    ? (Number) Double.parseDouble(number) : (Number) Long.parseLong(number);
        }

        private boolean next(char c) {
            spaces();
            if (at >= text.length() || text.charAt(at) != c) return false;
            at++;
            return true;
        }

        private void expect(char c) {
            if (!next(c)) throw error("'" + c + "'");
        }

        void spaces() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        IllegalArgumentException error(String expected) {
            return new IllegalArgumentException("json: expected " + expected + " at " + at);
        }
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
