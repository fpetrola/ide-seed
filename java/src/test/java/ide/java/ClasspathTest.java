package ide.java;

import com.github.javaparser.ast.Node;
import ide.program.Unresolved;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A library on the classpath is read like the JDK: its types bind, and javac compiles against it. */
class ClasspathTest {

    private static final Map<String, String> SOURCES = Map.of("a/U.java", """
            package a;

            import com.github.javaparser.ast.Node;

            class U {
                Node node;

                String text() {
                    return node.toString();
                }
            }
            """);

    /** The jar of JavaParser's core, which this module is compiled with: a library like any other. */
    private static Path library() throws URISyntaxException {
        return Path.of(Node.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void aLibraryOnTheClasspathBindsAndCompiles() throws URISyntaxException {
        var projection = JavaProjection.of(SOURCES, List.of(library()));

        assertThat(projection.program().outline()).contains("CLASS com.github.javaparser.ast.Node (library)",
                "a.U#node -> com.github.javaparser.ast.Node (declared)");
        assertThat(projection.program().unresolved()).isEmpty();
        assertThat(projection.diagnostics()).isEmpty();
    }

    @Test
    void withoutItTheLibraryBindsNothingAndDoesNotCompile() {
        var projection = JavaProjection.of(SOURCES);

        assertThat(projection.program().unresolved()).extracting(Unresolved::name).contains("Node");
        assertThat(projection.diagnostics()).isNotEmpty();
    }
}
