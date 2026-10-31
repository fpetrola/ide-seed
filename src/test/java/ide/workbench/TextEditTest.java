package ide.workbench;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextEditTest {

    private static final String P = """
            package a;

            class P {
                int x;
            }
            """;

    @Test
    void anEditedFileJoinsTheModelAndLaterRefactoringsSeeIt() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var edit = ws.edit("a/P.java", P.replace("    int x;\n", "    int x;\n\n    int twice() {\n        return x * 2;\n    }\n"));
        ws.invoke("rename", Map.of("element", "a.P#x", "name", "value"));

        assertThat(edit.applied()).isTrue();
        assertThat(ws.source("a/P.java")).contains("int value;", "return value * 2;");
    }

    @Test
    void editsAreUndoableLikeAnyTransaction() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        ws.edit("a/P.java", P.replace("int x;", "int x, y;"));
        ws.undo();

        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void unparsableTextIsRefusedAndTheModelKeepsTheLastGoodVersion() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var edit = ws.edit("a/P.java", P.replace("int x;", "int x"));

        assertThat(edit.applied()).isFalse();
        assertThat(edit.violations().getFirst().code()).isEqualTo("projection.unparsable");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void bindingsInUnchangedFilesFollowAChangeElsewhere() {
        var ws = Workspace.of(Map.of(
                "m/Entity.java", "package m;\n\npublic abstract class Entity {\n}\n",
                "m/Customer.java", "package m;\n\npublic class Customer extends Entity {\n    public String name;\n}\n",
                "m/Order.java", "package m;\n\npublic class Order {\n    public Customer customer;\n}\n",
                "s/Billing.java", "package s;\n\nimport m.Order;\n\nclass Billing {\n    String label(Order order) {\n        return order.customer.name;\n    }\n}\n"));
        assertThat(ws.references("m.Customer#name")).containsExactly("s.Billing#label(Order)");

        ws.edit("m/Order.java", "package m;\n\npublic class Order {\n    public Entity customer;\n}\n");

        // Billing was not re-parsed; types resolved on its nodes before the edit must not survive it
        assertThat(ws.references("m.Customer#name")).isEmpty();
    }
}
