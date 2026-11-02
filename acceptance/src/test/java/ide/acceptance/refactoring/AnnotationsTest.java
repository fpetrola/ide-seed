package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What an element is annotated with is a fact, as written, and {@code set} writes it, each above it, imported. */
class AnnotationsTest {

    @Test
    void anInterfaceIsAnnotatedAboveItsDeclaration() {
        var ws = Workspace.of(Map.of("a/RowProcessor.java", """
                package a;

                import java.util.List;

                public interface RowProcessor {
                    void processRow(List<String> fields) throws Exception;
                }
                """));

        var result = ws.invoke("set", Map.of("element", "a.RowProcessor", "attribute", "annotations", "value", "@FunctionalInterface"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/RowProcessor.java")).contains("""
                @FunctionalInterface
                public interface RowProcessor {
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aMethodsAnnotationsAreReplacedAndAQualifiedOneImported() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                public class P {
                    @Deprecated
                    public String toString() {
                        return "p";
                    }
                }
                """));

        var result = ws.invoke("set", Map.of("element", "a.P#toString()", "attribute", "annotations",
                "value", "@Override @javax.annotation.processing.Generated(\"ide\")"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).contains("""
                    @Override
                    @Generated("ide")
                    public String toString() {
                """).contains("import javax.annotation.processing.Generated;").doesNotContain("@Deprecated");
        assertThat(ws.errors()).isEmpty();
    }
}
