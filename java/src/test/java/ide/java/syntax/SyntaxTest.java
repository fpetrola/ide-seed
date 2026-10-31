package ide.java.syntax;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ide.java.text.EditSet;
import ide.java.text.Sources;
import ide.java.text.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/** The syntax layer as a product: Java constructs edited as text, in the layout the code already has. */
class SyntaxTest {

    private final Sources sources = new Sources(new ParserConfiguration(), () -> {
    });

    /** The text of a one-file program after the edits {@code edit} makes on its unit. */
    private String after(String text, BiConsumer<EditSet, Unit> edit) {
        sources.put(Map.of("a/A.java", text));
        var edits = new EditSet();
        edit.accept(edits, sources.unit("a/A.java"));
        sources.apply(edits);
        return sources.unit("a/A.java").text();
    }

    private static TypeDeclaration<?> type(Unit unit) {
        return unit.tree().getType(0);
    }

    private static MethodDeclaration method(Unit unit, String name) {
        return type(unit).getMethodsByName(name).getFirst();
    }

    @Test
    void aMemberGoesAtTheEndAfterABlankLineAndAFieldAfterTheLastField() {
        assertThat(after("package a;\n\npublic class A {\n}\n",
                (e, u) -> Members.append(e, u, type(u), Render.emptyMethod("m", Members.indentation(u, type(u))))))
                .isEqualTo("package a;\n\npublic class A {\n\n    void m() {\n    }\n}\n");
        assertThat(after("package a;\n\nclass A {\n    int x;\n\n    void m() {\n    }\n}\n",
                (e, u) -> Members.appendField(e, u, type(u), "Object y;")))
                .isEqualTo("package a;\n\nclass A {\n    int x;\n    Object y;\n\n    void m() {\n    }\n}\n");
        assertThat(after("package a;\n\nclass A\n        extends B {\n    void m() {\n    }\n}\n",
                (e, u) -> Members.appendField(e, u, type(u), "Object y;")))
                .isEqualTo("package a;\n\nclass A\n        extends B {\n    Object y;\n\n    void m() {\n    }\n}\n");
    }

    @Test
    void aMemberLeavesWithItsCommentAndIsWrittenElsewhereAtThatIndentation() {
        var text = """
                package a;

                class A {

                    // the answer
                    int answer() {
                        return 42;
                    }

                    int other() {
                        return 0;
                    }
                }
                """;
        sources.put(Map.of("a/A.java", text));
        var unit = sources.unit("a/A.java");
        assertThat(Members.text(unit, method(unit, "answer"), "        "))
                .isEqualTo("// the answer\n        int answer() {\n            return 42;\n        }");

        assertThat(after(text, (e, u) -> Members.remove(e, u, method(u, "answer")))).isEqualTo("""
                package a;

                class A {

                    int other() {
                        return 0;
                    }
                }
                """);
        assertThat(after(text, (e, u) -> Members.insertBefore(e, u, method(u, "answer"), "int x;"))).contains("""
                class A {

                    int x;

                    // the answer
                """);
    }

    @Test
    void modifiersAreKeywordsInTheirPlace() {
        assertThat(after("package a;\nclass A {\n    int x;\n}\n",
                (e, u) -> Modifiers.visibility(e, u, type(u).getFields().getFirst(), "private")))
                .contains("    private int x;");
        assertThat(after("package a;\nclass A {\n    public static int x;\n}\n",
                (e, u) -> Modifiers.visibility(e, u, type(u).getFields().getFirst(), "private")))
                .contains("    private static int x;");
        assertThat(after("package a;\nclass A {\n    private int x;\n}\n",
                (e, u) -> Modifiers.visibility(e, u, type(u).getFields().getFirst(), "package")))
                .contains("    int x;");
        assertThat(after("package a;\nclass A {\n    @Override\n    public String toString() {\n        return \"\";\n    }\n    @Deprecated\n    void m() {\n    }\n}\n",
                (e, u) -> Modifiers.visibility(e, u, method(u, "m"), "protected")))
                .contains("    @Deprecated\n    protected void m() {");
        assertThat(after("package a;\nclass A {\n    public int x;\n    int y;\n    private final int z = 0;\n}\n", (e, u) -> {
            Modifiers.flag(e, u, type(u).getFields().get(0), Modifier.Keyword.STATIC, true);
            Modifiers.flag(e, u, type(u).getFields().get(1), Modifier.Keyword.STATIC, true);
            Modifiers.flag(e, u, type(u).getFields().get(2), Modifier.Keyword.FINAL, false);
        })).contains("    public static int x;\n    static int y;\n    private int z = 0;");
    }

    @Test
    void parametersAndArgumentsAreListsOfTextsSeparatedByCommas() {
        var text = """
                package a;
                class A {
                    void none() {
                        some(1, 2);
                        none();
                    }
                    void some(int a, long b) {
                    }
                }
                """;
        var appended = after(text, (e, u) -> {
            Lists.append(e, u, method(u, "none").getParameters(), method(u, "none").getName(), "int c");
            Lists.append(e, u, method(u, "some").getParameters(), method(u, "some").getName(), "int c");
            for (var call : u.tree().findAll(MethodCallExpr.class)) Lists.append(e, u, call.getArguments(), call.getName(), "0");
        });
        assertThat(appended).contains("void none(int c) {", "some(1, 2, 0);", "none(0);", "void some(int a, long b, int c) {");

        var reordered = after(text, (e, u) -> {
            var params = method(u, "some").getParameters();
            Lists.rewrite(e, u, params, List.of(u.textOf(params.get(1)), u.textOf(params.get(0))));
            Lists.rewrite(e, u, method(u, "none").getBody().orElseThrow().findFirst(MethodCallExpr.class).orElseThrow().getArguments(), List.of());
        });
        assertThat(reordered).contains("void some(long b, int a) {", "some();");
    }

    @Test
    void importsGoAfterTheLastOneOrInABlockOfTheirOwn() {
        assertThat(after("package b;\n\nclass C {\n}\n", (e, u) -> Imports.add(e, u, "a.A")))
                .isEqualTo("package b;\n\nimport a.A;\n\nclass C {\n}\n");
        assertThat(after("package b;\n\nimport a.A;\n\nclass C {\n}\n", (e, u) -> Imports.add(e, u, "a.B")))
                .isEqualTo("package b;\n\nimport a.A;\nimport a.B;\n\nclass C {\n}\n");
        assertThat(after("package b;\n\nimport a.A;\n\nclass C {\n}\n", (e, u) -> Imports.remove(e, u, u.tree().getImport(0))))
                .isEqualTo("package b;\n\nclass C {\n}\n");
        assertThat(after("package b;\n\nimport a.A;\n\nclass C {\n}\n", (e, u) -> Imports.replace(e, u, u.tree().getImport(0), "c.A")))
                .isEqualTo("package b;\n\nimport c.A;\n\nclass C {\n}\n");
    }

    @Test
    void aBodyIsRenderedOneStatementPerLineAtTheMethodsIndentation() {
        assertThat(after("package a;\nclass A {\n    int get() {\n    }\n}\n",
                (e, u) -> e.replace(u, method(u, "get").getBody().orElseThrow(), Render.block("int y = 1;\nreturn y;", "    "))))
                .contains("    int get() {\n        int y = 1;\n        return y;\n    }");
    }
}
