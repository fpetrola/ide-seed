package ide.ui;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Dialogs that answer what a test says, and remember what they were asked. */
final class ScriptedDialogs implements Dialogs {

    final List<Form> filled = new ArrayList<>();
    final List<List<String>> offered = new ArrayList<>();
    final List<String> asked = new ArrayList<>();
    final List<String> told = new ArrayList<>();
    private final Deque<Map<String, Object>> fills = new ArrayDeque<>();
    private final Deque<String> choices = new ArrayDeque<>();
    private final Deque<String> lines = new ArrayDeque<>();

    /** The next form is filled with these values over the ones it came with. */
    ScriptedDialogs fill(Map<String, Object> values) {
        fills.add(values);
        return this;
    }

    ScriptedDialogs choose(String option) {
        choices.add(option);
        return this;
    }

    ScriptedDialogs line(String answer) {
        lines.add(answer);
        return this;
    }

    @Override
    public Optional<Map<String, Object>> fill(String title, Form form, Function<Map<String, Object>, String> preview) {
        filled.add(form);
        if (fills.isEmpty()) return Optional.empty();
        var values = new LinkedHashMap<String, Object>();
        form.fields().forEach(field -> { if (field.value() != null) values.put(field.param().name(), field.value()); });
        values.putAll(fills.poll());
        return Optional.of(values);
    }

    @Override
    public Optional<String> choose(String title, List<Option> options, Point at) {
        offered.add(options.stream().map(Option::text).toList());
        return Optional.ofNullable(choices.poll());
    }

    @Override
    public Optional<String> line(String title, String initial, Point at) {
        asked.add(title + " [" + initial + "]");
        return Optional.ofNullable(lines.poll());
    }

    @Override
    public void tell(String title, List<String> lines, Point at) {
        told.add(title);
    }
}
