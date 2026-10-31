package ide.samples;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Refactorings over a realistic sample: each case either applies (and still compiles) or aborts with a code. */
class OrdersSampleTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                arguments("encapsulate a public field read from another package",
                        "encapsulate-field", Map.of("field", "orders.model.Customer#name"), "applied"),
                arguments("encapsulating a field updated with += elsewhere",
                        "encapsulate-field", Map.of("field", "orders.model.Customer#discount"), "retarget.update"),
                arguments("rename a field used in an expression",
                        "rename", Map.of("element", "orders.model.Line#qty", "name", "quantity"), "applied"),
                arguments("rename an interface method with its implementations",
                        "rename-method-family", Map.of("method", "orders.model.Priced#total()", "name", "sum"), "applied"),
                arguments("pull up a public field to the abstract superclass",
                        "pull-up-field", Map.of("field", "orders.model.Customer#discount"), "applied"),
                arguments("move a type to the package of most of its users",
                        "move-type", Map.of("type", "orders.model.Line", "package", "orders.service"), "applied"),
                arguments("move an enum to another package",
                        "move-type", Map.of("type", "orders.model.Status", "package", "orders.service"), "applied"),
                arguments("delete a method in use",
                        "safe-delete", Map.of("element", "orders.model.Line#amount()"), "delete.referenced"),
                arguments("extract a superclass with one field",
                        "extract-superclass", Map.of("type", "orders.model.Line", "name", "Item", "fields", List.of("orders.model.Line#product")), "applied"),
                arguments("add a parameter with a default for every call",
                        "change-signature", Map.of("method", "orders.model.Line#amount()", "parameters", "rate:double=1.0"), "applied"),
                arguments("rename a method onto another with the same signature",
                        "rename", Map.of("element", "orders.model.Order#lines()", "name", "total"), "projection.duplicate"),
                arguments("rename a method into an overload of another",
                        "rename", Map.of("element", "orders.model.Order#add(Line)", "name", "total"), "applied"),
                arguments("rename an implementation alone breaks the interface",
                        "rename", Map.of("element", "orders.model.Order#total()", "name", "sum"), "dispatch.lost-override"),
                arguments("introduce a factory for a constructor",
                        "introduce-factory", Map.of("constructor", "orders.model.Line#new(String,int,double)", "name", "of"), "applied"),
                arguments("extract an interface from a class",
                        "extract-interface", Map.of("type", "orders.model.Line", "name", "Amount", "methods", List.of("orders.model.Line#amount()")), "applied"),
                arguments("extract a loop that updates a local used afterwards",
                        "extract-method", Map.of("method", "orders.model.Order#total()", "lines", "22-24", "name", "accumulate"), "applied"),
                arguments("inline a getter called on another receiver",
                        "inline-method", Map.of("method", "orders.model.Order#lines()"), "inline.receiver"),
                arguments("rename a field that has no accessors",
                        "rename-field-with-accessors", Map.of("field", "orders.model.Customer#name", "name", "fullName"), "applied"),
                arguments("encapsulate every public field of a type",
                        "encapsulate-fields", Map.of("type", "orders.model.Order"), "applied"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void refactoring(String description, String refactoring, Map<String, ?> args, String expected) throws IOException {
        var ws = Workspace.of(sources());
        assertThat(ws.diagnostics()).as("sample compiles").isEmpty();

        var result = ws.invoke(refactoring, args);

        if (expected.equals("applied")) {
            assertThat(result.violations()).as(description).isEmpty();
            assertThat(result.applied()).isTrue();
            assertThat(ws.diagnostics()).as("still compiles").isEmpty();
        } else {
            assertThat(result.applied()).as(description).isFalse();
            assertThat(result.violations()).extracting(Violation::code).contains(expected);
            assertThat(ws.diagnostics()).isEmpty();
        }
    }

    @Test
    void encapsulateFieldsSkipsWhatCannotBeEncapsulatedAndKeepsTheRest() throws IOException {
        var ws = Workspace.of(sources());

        var result = ws.invoke("encapsulate-fields", Map.of("type", "orders.model.Customer"));

        assertThat(result.applied()).isTrue();
        assertThat(ws.source("orders/model/Customer.java"))
                .contains("private String name;", "public String getName()", "public int discount;")
                .doesNotContain("getDiscount");
        assertThat(ws.source("orders/service/Billing.java")).contains("order.customer.getName()");
        assertThat(result.steps()).anyMatch(step -> step.startsWith("  X orders.model.Customer#discount: retarget.update"));
        assertThat(ws.diagnostics()).isEmpty();
    }

    static Map<String, String> sources() throws IOException {
        var root = Path.of("src/test/resources/samples/orders");
        var sources = new TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                sources.put(root.relativize(file).toString(), Files.readString(file));
            }
        }
        return sources;
    }
}
