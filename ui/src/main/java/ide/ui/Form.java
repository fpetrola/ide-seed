package ide.ui;

import ide.core.Capability;
import ide.core.Param;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parameters as fields to fill in. An element field offers the workspace's elements of the kinds it takes and comes
 * with the element under the caret where its kind fits; a choice offers its choices; several elements start as none,
 * a truth as false. A form is a capability's schema, or the questions a quick fix asks.
 */
final class Form {

    /** The element under the caret, and its kind. */
    record Selection(String element, String kind) {

        /** Whether a parameter takes it: one that takes elements of its kind. */
        boolean fits(Param param) {
            return param.type().startsWith("element") && (param.kinds().isEmpty() || param.kinds().contains(kind));
        }
    }

    /** A parameter, what it offers to choose from, if anything, and the value it starts with. */
    record Field(Param param, List<String> options, Object value) {
    }

    private final String title;
    private final List<Field> fields;

    private Form(String title, List<Field> fields) {
        this.title = title;
        this.fields = List.copyOf(fields);
    }

    static Form of(Capability capability, Ide ide, Optional<Selection> selection) {
        var fields = new ArrayList<Field>();
        var chosen = false;
        for (var param : capability.params()) {
            switch (param.type()) {
                case "element", "elements" -> {
                    var kinds = String.join(",", param.kinds());
                    var options = ide.list("elements", kinds.isEmpty() ? Map.of() : Map.of("kinds", kinds));
                    Object value = param.type().equals("elements") ? List.of() : null;
                    if (!chosen && param.type().equals("element") && selection.filter(s -> s.fits(param)).isPresent()) {
                        value = selection.get().element();
                        chosen = true;
                    }
                    fields.add(new Field(param, options, value));
                }
                case "choice" -> fields.add(new Field(param, param.kinds(), null));
                case "bool" -> fields.add(new Field(param, List.of(), false));
                default -> fields.add(new Field(param, List.of(), null));
            }
        }
        return new Form(capability.id() + ": " + capability.description(), fields);
    }

    /** The questions a quick fix asks ({@code name}, {@code question}, {@code default}), each answered as text. */
    static Form questions(String title, List<Map<String, Object>> asks) {
        return new Form(title, asks.stream().map(ask -> new Field(new Param(String.valueOf(ask.get("name")), "text", List.of(),
                String.valueOf(ask.get("question")), true), List.of(), ask.get("default"))).toList());
    }

    String title() {
        return title;
    }

    List<Field> fields() {
        return fields;
    }

    /** The arguments the values give: what is blank is left out, and the capability says what it misses. */
    Map<String, Object> args(Map<String, Object> values) {
        var args = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            var value = values.get(field.param().name());
            if (value == null || value instanceof String text && text.isBlank()) continue;
            args.put(field.param().name(), value);
        }
        return args;
    }
}
