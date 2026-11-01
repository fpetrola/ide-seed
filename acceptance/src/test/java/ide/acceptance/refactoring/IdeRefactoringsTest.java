package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Refactorings every IDE has, written in the catalog with no code of any language and checked by the compiler. */
class IdeRefactoringsTest {

    private static final String POINT = """
            package a;

            public class Point {
                private int x;
                private int y;
                int calls;

                int sum() {
                    return x + y;
                }

                int twice(int value) {
                    return value * 2;
                }
            }
            """;

    @Test
    void generateConstructorTakesAValueForEachFieldAndAssignsIt() {
        var ws = Workspace.of(Map.of("a/Point.java", POINT));

        var result = ws.invoke("generate-constructor", Map.of("type", "a.Point", "fields", List.of("a.Point#x", "a.Point#y")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Point.java")).contains("""
                    public Point(int x, int y) {
                        this.x = x;
                        this.y = y;
                    }
                """);
    }

    @Test
    void makeFinalIsKeptWhenNothingAssignsTheFieldAndUndoneWhenSomethingDoesOrNothingSetsIt() {
        var initialized = POINT.replace("int calls;", "int calls = 0;");
        var ws = Workspace.of(Map.of("a/Point.java", initialized));

        assertThat(ws.invoke("make-final", Map.of("field", "a.Point#calls")).applied()).isTrue();
        assertThat(ws.source("a/Point.java")).contains("final int calls = 0;");

        var counted = Workspace.of(Map.of("a/Point.java", initialized.replace("return value * 2;", "calls++;\n        return value * 2;")));
        assertThat(counted.invoke("make-final", Map.of("field", "a.Point#calls")).violations()).extracting(Violation::code)
                .contains("ecj.FinalFieldAssignment");
        var x = ws.invoke("make-final", Map.of("field", "a.Point#x"));
        assertThat(x.violations()).extracting(Violation::code)
                .contains("ecj.UninitializedBlankFinalField");
    }

    @Test
    void makeStaticIsKeptWhenTheMethodUsesNoObjectAndUndoneWhenItDoes() {
        var ws = Workspace.of(Map.of("a/Point.java", POINT));

        assertThat(ws.invoke("make-static", Map.of("method", "a.Point#twice(int)")).applied()).isTrue();
        assertThat(ws.source("a/Point.java")).contains("static int twice(int value) {");
        assertThat(ws.invoke("make-static", Map.of("method", "a.Point#sum()")).applied()).isFalse();
    }

    @Test
    void generateDelegatesHandsEachCallOnToWhatTheFieldHolds() {
        var ws = Workspace.of(Map.of("a/Point.java", POINT, "a/Tracker.java", """
                package a;

                public class Tracker {
                    private Point point;
                }
                """));

        var result = ws.invoke("generate-delegates", Map.of("field", "a.Tracker#point", "methods", List.of("a.Point#sum()", "a.Point#twice(int)")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Tracker.java")).contains("""
                    public int sum() {
                        return point.sum();
                    }
                """, """
                    public int twice(int value) {
                        return point.twice(value);
                    }
                """);
    }

    @Test
    void renameWithDelegateKeepsTheOldNameCallingTheNewOne() {
        var ws = Workspace.of(Map.of("a/Point.java", POINT, "a/User.java", """
                package a;

                class User {
                    int use(Point p) {
                        return p.twice(3);
                    }
                }
                """));

        var result = ws.invoke("rename-with-delegate", Map.of("method", "a.Point#twice(int)", "name", "doubled"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Point.java")).contains("int doubled(int value) {", """
                    int twice(int value) {
                        return doubled(value);
                    }
                """);
        assertThat(ws.source("a/User.java")).contains("return p.doubled(3);");
    }
}
