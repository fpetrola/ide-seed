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
 * with the element under the caret, or the one the caret is inside, where its kind fits; lines come with the lines
 * selected; a text comes with what the refactoring suggests from the rest (its default name); several elements offer
 * what the refactoring narrows them to (the fields of the type chosen), if it does; a choice offers its
 * choices; several elements start as none, a truth as false. A form is a capability's schema, or the questions a
 * quick fix asks.
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
        return of(capability, ide, selection.stream().toList(), Optional.empty());
    }

    /** A capability's form, with the first of the candidates each element field takes, and the lines selected. */
    static Form of(Capability capability, Ide ide, List<Selection> candidates, Optional<String> lines) {
        var fields = new ArrayList<Field>();
        var used = new ArrayList<Selection>();
        for (var param : capability.params()) {
            switch (param.type()) {
                case "element", "elements" -> {
                    var kinds = String.join(",", param.kinds());
                    var options = ide.records("elements", kinds.isEmpty() ? Map.of() : Map.of("kinds", kinds)).stream()
                            .map(element -> String.valueOf(element.get("element"))).toList();
                    Object value = param.type().equals("elements") ? List.of() : null;
                    if (param.type().equals("element")) {
                        var fit = candidates.stream().filter(s -> !used.contains(s) && s.fits(param)).findFirst();
                        if (fit.isPresent()) {
                            value = fit.get().element();
                            used.add(fit.get());
                        }
                    }
                    fields.add(new Field(param, options, value));
                }
                case "choice" -> fields.add(new Field(param, param.kinds(), null));
                case "bool" -> fields.add(new Field(param, List.of(), false));
                case "lines" -> fields.add(new Field(param, List.of(), lines.orElse(null)));
                default -> fields.add(new Field(param, List.of(), null));
            }
        }
        var form = new Form(capability.id() + ": " + capability.description(), fields);
        return form.suggested(ide, capability.id());
    }

    /** The fields left blank, filled with what the refactoring suggests from the others, if it suggests anything. */
    private Form suggested(Ide ide, String refactoring) {
        if (!ide.has("suggest")) return this;
        var given = new LinkedHashMap<String, Object>();
        fields.stream().filter(f -> f.value() != null && !(f.value() instanceof List<?> l && l.isEmpty()) && !(f.value() instanceof Boolean))
                .forEach(f -> given.put(f.param().name(), f.value()));
        Map<String, Object> suggested;
        try {
            suggested = ide.record("suggest", Map.of("refactoring", refactoring, "args", given));
        } catch (RuntimeException unsuggested) {
            return this;
        }
        var narrowed = fields.stream().map(f -> {
            if (!f.param().type().startsWith("element") || given.containsKey(f.param().name()) && f.param().type().equals("element")) return f;
            var options = ide.call("options", Map.of("refactoring", refactoring, "param", f.param().name(), "args", given));
            return options instanceof List<?> some ? new Field(f.param(), some.stream().map(String::valueOf).toList(), f.value()) : f;
        }).toList();
        return new Form(title, narrowed.stream().map(f -> f.value() == null && suggested != null && suggested.containsKey(f.param().name())
                ? new Field(f.param(), f.options(), String.valueOf(suggested.get(f.param().name()))) : f).toList());
    }

    /** A capability's form as a record is filled in: each field starting with its value, if it has one. */
    static Form filled(String title, Capability capability, Map<String, ?> values) {
        return new Form(title, capability.params().stream().map(param -> new Field(param,
                param.type().equals("choice") ? param.kinds() : List.of(), values.get(param.name()))).toList());
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
