package ide.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What happened, as a tree of steps: each has a kind, a label, and the steps inside it. A transaction keeps what ran
 * in one, the repairer how it searched in another, the planner how it deduced in a third. Writing a trace as lines
 * happens in one place, {@link #lines()}: two spaces per level, then the kind's sigil, then the label.
 */
public final class Trace {

    /** What a step is, and the sigil it is written with. */
    public enum Kind {
        /** A refactoring was called; its steps are inside. */
        CALL("."),
        /** A primitive was applied. */
        PRIMITIVE("'"),
        /** Something to answer: a violation to repair, a goal to reach. */
        QUESTION("?"),
        /** What answered it: a repair kept, a plan found. */
        ANSWER("!"),
        /** What was tried and dropped, and why. */
        DROPPED("X"),
        /** What could not be decided, and is assumed. */
        ASSUMED(":"),
        /** A remark. */
        NOTE("");

        private final String sigil;

        Kind(String sigil) {
            this.sigil = sigil;
        }

        public String sigil() {
            return sigil;
        }
    }

    /** A step: what it is, what it says, and the steps inside it. */
    public record Step(Kind kind, String label, List<Step> steps) {
        public Step {
            steps = List.copyOf(steps);
        }
    }

    /** A step while the trace is being written: the steps inside an open one keep arriving. */
    private record Open(Kind kind, String label, List<Open> inside) {
        Step step() {
            return new Step(kind, label, inside.stream().map(Open::step).toList());
        }
    }

    private final List<Open> roots = new ArrayList<>();
    private final Deque<Open> open = new ArrayDeque<>();

    /** A step at the current level. */
    public Trace add(Kind kind, String label) {
        level().add(new Open(kind, label, new ArrayList<>()));
        return this;
    }

    /** A step whose following steps go inside it, until {@link #close()}. */
    public Trace open(Kind kind, String label) {
        var step = new Open(kind, label, new ArrayList<>());
        level().add(step);
        open.push(step);
        return this;
    }

    public Trace close() {
        open.pop();
        return this;
    }

    /** The steps of a finished trace, at the current level of this one. */
    public Trace append(Trace other) {
        level().addAll(other.roots);
        return this;
    }

    public List<Step> steps() {
        return roots.stream().map(Open::step).toList();
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /** The trace as lines: two spaces per level, the kind's sigil, the label. */
    public List<String> lines() {
        var lines = new ArrayList<String>();
        write(steps(), 0, lines);
        return lines;
    }

    private static void write(List<Step> steps, int depth, List<String> lines) {
        for (var step : steps) {
            var sigil = step.kind().sigil();
            lines.add("  ".repeat(depth) + (sigil.isEmpty() ? "" : sigil + " ") + step.label());
            write(step.steps(), depth + 1, lines);
        }
    }

    private List<Open> level() {
        return open.isEmpty() ? roots : open.peek().inside();
    }
}
