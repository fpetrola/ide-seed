package ide.session;

import ide.engine.Plan;
import ide.engine.Repairer;
import ide.engine.Violation;
import ide.java.JavaProjection;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.refactoring.Library;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The session alone: each operation is a transaction over the project, kept only if it did what it says. */
class SessionTest {

    private static final String P = """
            package a;

            class P {
                int twice() {
                    return total * 2;
                }
            }
            """;

    private static Session session(JavaProjection project) {
        return new Session(project, Library.standard(), new Repairer(List.of()));
    }

    @Test
    void aFixThatLeavesItsDiagnosticIsNotKept() {
        var project = JavaProjection.of(Map.of("a/P.java", P));
        var session = session(project);
        var nothing = new Plan("do nothing", List.of(), (projection, answers) -> { });

        var result = session.fix(new Fix(project.diagnostics().getFirst(), nothing), Map.of());

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("fix.ineffective");
        assertThatThrownBy(session::undo).hasMessageContaining("nothing to undo");
    }

    @Test
    void aFixThatRemovesItsDiagnosticIsKeptAndCanBeUndone() {
        var project = JavaProjection.of(Map.of("a/P.java", P));
        var session = session(project);
        var type = project.program().find("a.P").orElseThrow().id();
        var declare = new Plan("declare total", List.of(), (projection, answers) ->
                projection.apply(new Primitive.Create(Kind.FIELD, type, "total", Map.of(Fact.TYPE, "int"), null)));

        var result = session.fix(new Fix(project.diagnostics().getFirst(), declare), Map.of());

        assertThat(result.applied()).isTrue();
        assertThat(project.diagnostics()).isEmpty();
        session.undo();
        assertThat(project.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void definingRefactoringsIsAnEventToo(@TempDir Path dir) throws IOException {
        var session = session(JavaProjection.of(Map.of("a/P.java", P)));
        session.logTo(dir.resolve("events.jsonl"));

        session.define("""
                refactoring make-private "Makes a member private"
                  param member : element(FIELD,METHOD) "the member"
                  set ${member} visibility private
                end
                """);

        assertThat(Files.readAllLines(dir.resolve("events.jsonl"))).singleElement().asString()
                .contains("\"event\":\"define\"", "\"refactorings\":[\"make-private\"]");
    }
}
