package ide.program;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Where an element is, as the text names it. A type is addressed by its qualified name; a member by the id of its
 * owner and its name, so renaming or moving a type changes no member's address; a callable also by the keys of its
 * parameter types (the id of a workspace type, the name of any other); a parameter by the id of its callable; a local
 * by the id of its callable, its name, and which of the locals of that name it is (the first is 0); a statement of a
 * body by the id of its callable and its place among the body's statements (the first is 0).
 */
public sealed interface Address {

    record Package(String name) implements Address {
    }

    record Type(String qualified) implements Address {
    }

    record Field(ElementId owner, String name) implements Address {
    }

    record Callable(ElementId owner, String name, List<String> keys) implements Address {
        public Callable {
            keys = List.copyOf(keys);
        }
    }

    record Param(ElementId owner, String name) implements Address {
    }

    record Local(ElementId owner, String name, int ordinal) implements Address {
    }

    record Statement(ElementId owner, int index) implements Address {
    }

    /** The element this address hangs from: the owner of a member or parameter; null for packages and types. */
    default ElementId owner() {
        return switch (this) {
            case Field f -> f.owner();
            case Callable c -> c.owner();
            case Param p -> p.owner();
            case Local l -> l.owner();
            case Statement s -> s.owner();
            case Package p -> null;
            case Type t -> null;
        };
    }

    /** The same address under another owner (members and parameters only). */
    default Address withOwner(ElementId owner) {
        return switch (this) {
            case Field f -> new Field(owner, f.name());
            case Callable c -> new Callable(owner, c.name(), c.keys());
            case Param p -> new Param(owner, p.name());
            case Local l -> new Local(owner, l.name(), l.ordinal());
            case Statement s -> new Statement(owner, s.index());
            default -> throw new IllegalArgumentException("not owned: " + this);
        };
    }

    /** The same address with another name (members and parameters only; types are rebound by qualified name). */
    default Address withName(String name) {
        return switch (this) {
            case Field f -> new Field(f.owner(), name);
            case Callable c -> new Callable(c.owner(), name, c.keys());
            case Param p -> new Param(p.owner(), name);
            case Local l -> new Local(l.owner(), name, l.ordinal());
            default -> throw new IllegalArgumentException("renamed by qualified name: " + this);
        };
    }

    Pattern PACKAGE_PREFIX = Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+");

    /** A qualified name without its package segments (the ones starting lowercase): {@code a.Outer.Inner} is {@code Outer.Inner}. */
    static String simpleName(String qualified) {
        return PACKAGE_PREFIX.matcher(qualified).replaceAll("");
    }
}
