package ide.samples;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Generics, nested and inner classes, varargs, default methods, method references, static fields. */
class ShopSampleTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                arguments("rename a generic method called from its varargs overload",
                        "rename", Map.of("element", "shop.Catalog#add(T)", "name", "put"), "applied"),
                arguments("rename an interface method used through a method reference",
                        "rename-method-family", Map.of("method", "shop.Item#code()", "name", "id"), "applied"),
                arguments("rename a static nested class used with its outer name",
                        "rename", Map.of("element", "shop.Catalog.Entry", "name", "Key"), "applied"),
                arguments("rename a static field used through the type and with ++",
                        "rename", Map.of("element", "shop.Catalog#created", "name", "count"), "applied"),
                arguments("encapsulate a static field",
                        "encapsulate-field", Map.of("field", "shop.Catalog#created"), "applied"),
                arguments("rename a default method called from a lambda",
                        "rename", Map.of("element", "shop.Item#label()", "name", "title"), "applied"),
                arguments("extract an interface from a class that already implements one",
                        "extract-interface", Map.of("type", "shop.Book", "name", "Coded", "methods", java.util.List.of("shop.Book#code()")), "applied"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void refactoring(String description, String refactoring, Map<String, ?> args, String expected) throws IOException {
        var ws = Workspace.of(sources());
        assertThat(ws.diagnostics()).as("sample compiles").isEmpty();

        var result = ws.invoke(refactoring, args);

        if (expected.equals("applied")) {
            assertThat(result.violations()).as(description).isEmpty();
            assertThat(ws.diagnostics()).as("still compiles").isEmpty();
            assertThat(ws.model()).as("the kept model is the model of the text").isEqualTo(Workspace.of(ws.sources()).model());
        } else {
            assertThat(result.violations()).extracting(Violation::code).contains(expected);
        }
    }

    static Map<String, String> sources() throws IOException {
        var root = Path.of("src/test/resources/samples/shop");
        var sources = new TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file).toString(), Files.readString(file));
            }
        }
        return sources;
    }
}
