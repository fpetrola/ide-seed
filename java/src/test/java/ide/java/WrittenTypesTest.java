package ide.java;

import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A type written in a file names what the program knows: a simple name the file cannot name yet is imported when the
 * program knows one type of that name; of two, or none, it is left as written, a use that binds nothing.
 */
class WrittenTypesTest {

    @Test
    void aTypeTheProgramKnowsOneOfIsImported() {
        var projection = JavaProjection.of(Map.of(
                "a/Rows.java", "package a;\n\nimport java.util.List;\n\nclass Rows {\n    static void each(List<String> fields) {\n    }\n}\n",
                "a/RowProcessor.java", "package a;\n\ninterface RowProcessor {\n}\n"));
        var type = projection.program().find("a.RowProcessor").orElseThrow().id();

        projection.apply(new Primitive.Create(Kind.METHOD, type, "processRow", Map.of(Fact.TYPE, "void"), null));
        var method = projection.program().find("a.RowProcessor#processRow()").orElseThrow().id();
        projection.apply(new Primitive.Create(Kind.PARAM, method, "fields", Map.of(Fact.TYPE, "List<String>"), null));

        assertThat(projection.source("a/RowProcessor.java")).contains("import java.util.List;", "processRow(List<String> fields)");
        assertThat(projection.program().unresolved()).isEmpty();
    }

    @Test
    void aNameOfNoTypeTheProgramKnowsIsLeftAsWritten() {
        var projection = JavaProjection.of(Map.of("a/A.java", "package a;\n\nclass A {\n    int x;\n}\n"));

        projection.apply(new Primitive.Set(projection.program().find("a.A#x").orElseThrow().id(), "type", "Money"));

        assertThat(projection.source("a/A.java")).contains("    Money x;").doesNotContain("import");
    }

    @Test
    void soIsWhatAMethodDeclaresItThrows() {
        var projection = JavaProjection.of(Map.of(
                "a/Rows.java", "package a;\n\nimport java.sql.SQLException;\n\nclass Rows {\n    static void each() throws SQLException {\n    }\n}\n",
                "a/RowProcessor.java", "package a;\n\ninterface RowProcessor {\n    void processRow();\n}\n"));

        projection.apply(new Primitive.Set(projection.program().find("a.RowProcessor#processRow()").orElseThrow().id(), "throws", "SQLException"));

        assertThat(projection.source("a/RowProcessor.java")).contains("import java.sql.SQLException;", "void processRow() throws SQLException;");
        assertThat(projection.program().unresolved()).isEmpty();
    }
}
