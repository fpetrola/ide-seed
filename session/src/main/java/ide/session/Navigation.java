package ide.session;

import ide.program.Element;
import ide.program.Mode;
import ide.program.Program;
import ide.projection.Projection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What people ask of a program while they read it: who calls a method, where a type sits in its hierarchy, who uses
 * an element, and the whole model as text. Elements are named by address, and the answers are addresses too.
 */
public final class Navigation {

    private final Projection projection;

    public Navigation(Projection projection) {
        this.projection = projection;
    }

    /** Members whose code calls the method, or a method it overrides (such a call may dispatch to it). */
    public List<String> callers(String method) {
        var program = projection.program();
        var target = element(program, method);
        var called = new ArrayList<>(List.of(target));
        called.addAll(program.overriddenTransitively(target));
        return called.stream().flatMap(m -> program.referencesTo(m.id()).stream()).filter(r -> r.mode() == Mode.CALL)
                .map(r -> program.element(r.site()).qualified()).distinct().sorted().toList();
    }

    /** Supertypes (nearest first), the type marked with {@code >}, then subtypes indented by depth. */
    public List<String> hierarchy(String type) {
        var program = projection.program();
        var focus = element(program, type);
        var lines = new ArrayList<>(program.ancestors(focus).stream().map(Element::qualified).toList());
        lines.add("> " + focus.qualified());
        subtypes(program, focus, 1, lines);
        return lines;
    }

    /** The site of every use of an element, in reference order. */
    public List<String> references(String element) {
        var program = projection.program();
        return program.referencesTo(element(program, element).id()).stream()
                .map(r -> program.element(r.site()).qualified()).toList();
    }

    /** The model as sorted text: {@code KIND qualified} per element, {@code site -> target (mode)} per reference. */
    public List<String> model() {
        return projection.program().outline();
    }

    private static void subtypes(Program program, Element type, int depth, List<String> lines) {
        program.subtypes(type).stream().sorted(Comparator.comparing(Element::qualified))
                .forEach(t -> {
                    lines.add("  ".repeat(depth) + t.qualified());
                    subtypes(program, t, depth + 1, lines);
                });
    }

    private static Element element(Program program, String address) {
        return program.find(address).orElseThrow(() -> new IllegalArgumentException("no element " + address));
    }
}
