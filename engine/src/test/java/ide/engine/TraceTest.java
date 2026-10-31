package ide.engine;

import org.junit.jupiter.api.Test;

import static ide.engine.Trace.Kind.ANSWER;
import static ide.engine.Trace.Kind.ASSUMED;
import static ide.engine.Trace.Kind.CALL;
import static ide.engine.Trace.Kind.DROPPED;
import static ide.engine.Trace.Kind.NOTE;
import static ide.engine.Trace.Kind.PRIMITIVE;
import static ide.engine.Trace.Kind.QUESTION;
import static org.assertj.core.api.Assertions.assertThat;

/** A trace is a tree of steps; writing it as lines happens in one place. */
class TraceTest {

    @Test
    void stepsNestInsideTheStepsTheyBelongTo() {
        var trace = new Trace()
                .open(CALL, "extract-superclass")
                .add(PRIMITIVE, "create CLASS a.Base")
                .open(CALL, "pull-up-field")
                .add(PRIMITIVE, "move a.B#x -> a.Base")
                .close()
                .close()
                .add(ANSWER, "open-visibility a.Base#x private -> package");

        assertThat(trace.steps()).hasSize(2);
        assertThat(trace.steps().getFirst().steps()).extracting(Trace.Step::kind).containsExactly(PRIMITIVE, CALL);
        assertThat(trace.lines()).containsExactly(
                ". extract-superclass",
                "  ' create CLASS a.Base",
                "  . pull-up-field",
                "    ' move a.B#x -> a.Base",
                "! open-visibility a.Base#x private -> package");
    }

    @Test
    void everyKindHasItsSigilAndANoteHasNone() {
        var search = new Trace()
                .open(QUESTION, "access.insufficient a.A#n() cannot access private a.B#x")
                .add(DROPPED, "move back: would undo what the refactoring did")
                .add(ANSWER, "open a.B#x")
                .close()
                .open(QUESTION, "resolves(List)")
                .add(NOTE, "(no plan)")
                .close()
                .add(ASSUMED, "types were not checked");

        assertThat(search.lines()).containsExactly(
                "? access.insufficient a.A#n() cannot access private a.B#x",
                "  X move back: would undo what the refactoring did",
                "  ! open a.B#x",
                "? resolves(List)",
                "  (no plan)",
                ": types were not checked");
    }

    @Test
    void anotherTraceIsAppendedWhereThisOneIs() {
        var inner = new Trace().open(QUESTION, "dispatch.lost-override").add(NOTE, "(no plan)").close();

        var outer = new Trace().open(CALL, "try a.B#x").append(inner).close();

        assertThat(outer.lines()).containsExactly(". try a.B#x", "  ? dispatch.lost-override", "    (no plan)");
        assertThat(new Trace().isEmpty()).isTrue();
    }
}
