package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InlineMethodTest {

    @Test
    void inlinesEveryCallSubstitutingArgumentsWithMinimalParenthesesAndDeletesTheMethod() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int base;

                    int scaled(int k) {
                        return base * k;
                    }

                    int use(int v) {
                        return scaled(v + 1) + scaled(2) * 3;
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.P#scaled(int)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).isEqualTo("""
                package a;

                class P {
                    int base;

                    int use(int v) {
                        return base * (v + 1) + base * 2 * 3;
                    }
                }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void twoCallsInOneMethodAreInlinedAlthoughTheBodyUsesMoreThanOneThing() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int base;
                    int step;

                    int next(int k) {
                        return base + step * k;
                    }

                    int use(int v) {
                        return next(v) - next(2);
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.P#next(int)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).contains("return base + step * v - (base + step * 2);");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void anArgumentThatWouldBeEvaluatedTwiceIsRefused() {
        var p = """
                package a;

                class P {
                    int square(int k) {
                        return k * k;
                    }

                    int use(int v) {
                        return square(next(v));
                    }

                    int next(int v) {
                        return v + 1;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", p));

        var result = ws.invoke("inline-method", Map.of("method", "a.P#square(int)"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("inline.evaluation-count");
        assertThat(ws.source("a/P.java")).isEqualTo(p);
    }

    /**
     * A method of statements called as one is replaced by its statements, each parameter by its argument; called as a
     * lambda's body, the lambda's body becomes a block of them.
     */
    @Test
    void aMethodOfStatementsCalledAsOneIsReplacedByThem() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;
                import java.util.function.Consumer;

                class Rows {
                    int count;

                    void process(List<String> fields) {
                        each(fields, f -> row(f));
                        String first = fields.get(0);
                        row(first);
                    }

                    void each(List<String> fields, Consumer<String> action) {
                        fields.forEach(action);
                    }

                    void row(String field) {
                        if (field.isEmpty()) {
                            count++;
                        } else {
                            System.out.println(field);
                        }
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.Rows#row(String)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                    void process(List<String> fields) {
                        each(fields, f -> {
                            if (f.isEmpty()) {
                                count++;
                            } else {
                                System.out.println(f);
                            }
                        });
                        String first = fields.get(0);
                        if (first.isEmpty()) {
                            count++;
                        } else {
                            System.out.println(first);
                        }
                    }
                """).doesNotContain("void row(");
        assertThat(ws.errors()).isEmpty();
    }

    /** A return in the middle would leave the caller: it is refused. */
    @Test
    void aMethodOfStatementsThatReturnsIsRefused() {
        var source = """
                package a;

                class Rows {
                    void process(String field) {
                        row(field);
                        System.out.println("after");
                    }

                    void row(String field) {
                        if (field.isEmpty()) {
                            return;
                        }
                        System.out.println(field);
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Rows.java", source));

        var result = ws.invoke("inline-method", Map.of("method", "a.Rows#row(String)"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("inline.body-shape");
        assertThat(ws.source("a/Rows.java")).isEqualTo(source);
    }

    /** A local of the inlined statements named as one where the call is takes a name of its own there, as IntelliJ's. */
    @Test
    void aLocalNamedAsOneOfTheCallersIsRenamed() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                class Rows {
                    void process(String line) {
                        String name = line.trim();
                        row(line);
                        System.out.println(name);
                    }

                    void row(String line) {
                        String name = line.toUpperCase();
                        System.out.println(name + name.length());
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.Rows#row(String)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        String name = line.trim();
                        String name2 = line.toUpperCase();
                        System.out.println(name2 + name2.length());
                        System.out.println(name);
                """).doesNotContain("void row(");
        assertThat(ws.errors()).isEmpty();
    }

    /** A method reference is first the lambda that calls it, whose call is then inlined, as IntelliJ's. */
    @Test
    void aMethodReferenceBecomesALambdaWithTheBody() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;

                class Rows {
                    void process(List<String> lines) {
                        lines.forEach(this::row);
                    }

                    void row(String line) {
                        if (line.isEmpty()) {
                            System.out.println("empty");
                        } else {
                            System.out.println(line);
                        }
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.Rows#row(String)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        lines.forEach((line) -> {
                            if (line.isEmpty()) {
                                System.out.println("empty");
                            } else {
                                System.out.println(line);
                            }
                        });
                """).doesNotContain("void row(");
        assertThat(ws.errors()).isEmpty();
    }
}
