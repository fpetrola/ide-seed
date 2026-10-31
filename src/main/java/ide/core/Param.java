package ide.core;

import java.util.List;

/**
 * A capability parameter: {@code type} is text, bool, element, elements or choice; {@code kinds} restricts elements
 * (or lists the choices).
 */
public record Param(String name, String type, List<String> kinds, String description, boolean required) {

    public static Param of(String name, String type, String... kinds) {
        return new Param(name, type, List.of(kinds), "", true);
    }

    public Param optional() {
        return new Param(name, type, kinds, description, false);
    }
}
