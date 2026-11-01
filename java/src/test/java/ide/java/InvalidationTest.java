package ide.java;

import ide.program.Body;
import ide.program.ElementId;
import ide.program.Primitive;
import ide.program.Program;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a change the program is read again, but the uses of a file are resolved again only when the change can
 * affect them: the files it touched, the files that name a member whose declaration it changed, and every file when
 * it changed a type itself.
 */
class InvalidationTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/A.java", "package a;\n\npublic class A {\n    public int x;\n    public int y;\n\n    int twice() {\n        return x * 2;\n    }\n}\n",
            "a/B.java", "package a;\n\nclass B {\n    int use(A a) {\n        return a.x;\n    }\n}\n",
            "a/C.java", "package a;\n\nclass C {\n    int other(A a) {\n        return a.y;\n    }\n}\n",
            "a/D.java", "package a;\n\nclass D {\n    int none() {\n        return 1;\n    }\n}\n");

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    @Test
    void aFirstReadingResolvesEveryFile() {
        var projection = JavaProjection.of(SOURCES);

        projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/A.java", "a/B.java", "a/C.java", "a/D.java");
    }

    @Test
    void aChangeInsideABodyResolvesOnlyItsFile() {
        var projection = JavaProjection.of(SOURCES);

        var p = projection.program();
        projection.apply(new Primitive.Create(ide.program.Kind.STATEMENT, id(p, "a.A#twice()"), "",
                Map.of(ide.program.Fact.BODY, "return " + Body.name(id(p, "a.A#x"))), null));
        projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/A.java");
    }

    @Test
    void renamingAMemberResolvesTheFilesItTouchedAndTheFilesThatNameIt() {
        var projection = JavaProjection.of(SOURCES);

        projection.apply(new Primitive.Rename(id(projection.program(), "a.A#y"), "count")); // touches A and C
        var after = projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/A.java", "a/C.java");
        assertThat(after.referencesTo(id(after, "a.A#count"))).extracting(r -> after.element(r.site()).qualified())
                .containsExactly("a.C#other(A)");
    }

    @Test
    void anEditByHandOfAMemberResolvesTheFilesThatNameIt() {
        var projection = JavaProjection.of(SOURCES);
        projection.program();

        projection.edit("a/A.java", SOURCES.get("a/A.java").replace("public int y;", "public long y;"));
        projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/A.java", "a/C.java");
    }

    @Test
    void aMemberAddedToATypeIsSeenWhereASubtypeUsesThatNameUnqualified() {
        var t = "package a;\n\npublic class T {\n}\n";
        var projection = JavaProjection.of(Map.of("a/T.java", t,
                "a/U.java", "package a;\n\npublic class U {\n    int f0;\n\n    class Inner extends T {\n        int g() {\n            return f0;\n        }\n    }\n}\n"));
        projection.program();

        projection.edit("a/T.java", t.replace("{\n}", "{\n    public int f0;\n}")); // Inner's f0 is T's now, not U's
        var after = projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/T.java", "a/U.java");
        assertThat(after.referencesFrom(id(after, "a.U.Inner#g()"))).extracting(r -> after.element(r.target()).qualified())
                .containsExactly("a.T#f0");
    }

    @Test
    void anOverloadAddedToATypeIsSeenWhereACallOnASubtypeMayPickIt() {
        var t = "package a;\n\npublic class T {\n}\n";
        var projection = JavaProjection.of(Map.of("a/T.java", t,
                "a/S.java", "package a;\n\npublic class S extends T {\n    public void m(Object o) {\n    }\n}\n",
                "a/U.java", "package a;\n\nclass U {\n    void use(S s) {\n        s.m(1);\n    }\n}\n"));
        projection.program();

        projection.edit("a/T.java", t.replace("{\n}", "{\n    public void m(int i) {\n    }\n}")); // more specific
        var after = projection.program();

        assertThat(projection.resolvedLastTime()).contains("a/U.java");
        assertThat(after.referencesFrom(id(after, "a.U#use(S)"))).extracting(r -> after.element(r.target()).qualified())
                .contains("a.T#m(int)");
    }

    @Test
    void changingATypeResolvesEveryFile() {
        var projection = JavaProjection.of(SOURCES);

        projection.apply(new Primitive.Rename(id(projection.program(), "a.A"), "Base")); // its file is renamed too
        projection.program();

        assertThat(projection.resolvedLastTime()).containsExactly("a/B.java", "a/Base.java", "a/C.java", "a/D.java");
    }
}
