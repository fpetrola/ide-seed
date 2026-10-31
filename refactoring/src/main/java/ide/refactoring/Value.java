package ide.refactoring;

import ide.program.ElementId;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What refactorings pass each other and scripts compute: an element or a reference (by id), text, a truth value, or
 * a list. Java's {@code null} is no value. Whether a text names an element by id or by address is settled once, in
 * {@link Args}; from there on an element is an {@link Elem}.
 */
public sealed interface Value {

    record Elem(ElementId id) implements Value {
        @Override
        public String text() {
            return id.value();
        }
    }

    record Ref(String id) implements Value {
        @Override
        public String text() {
            return id;
        }
    }

    record Text(String text) implements Value {
    }

    record Bool(boolean value) implements Value {
        @Override
        public String text() {
            return String.valueOf(value);
        }
    }

    record Items(List<Value> items) implements Value {
        public Items {
            items = List.copyOf(items);
        }

        @Override
        public String text() {
            return items.stream().map(Value::asText).collect(Collectors.joining(", "));
        }
    }

    /** The value as text is written with it: elements and references by id, lists joined by commas. */
    String text();

    /** A value from outside: element ids become elements, collections lists, anything else text. */
    static Value of(Object raw) {
        return switch (raw) {
            case null -> null;
            case Value value -> value;
            case ElementId id -> new Elem(id);
            case Boolean b -> new Bool(b);
            case Collection<?> collection -> new Items(collection.stream().map(Value::of).toList());
            default -> new Text(String.valueOf(raw));
        };
    }

    /** As text, no value included. */
    static String asText(Value value) {
        return value == null ? "null" : value.text();
    }

    static boolean truthy(Value value) {
        return switch (value) {
            case null -> false;
            case Bool b -> b.value();
            case Text t -> !t.text().isEmpty();
            case Items i -> !i.items().isEmpty();
            case Elem e -> true;
            case Ref r -> true;
        };
    }

    /** The items of a list; a single value is a list of one, no value an empty list. */
    static List<Value> items(Value value) {
        return switch (value) {
            case null -> List.of();
            case Items i -> i.items();
            default -> List.of(value);
        };
    }
}
