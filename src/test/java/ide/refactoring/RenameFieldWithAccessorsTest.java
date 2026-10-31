package ide.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenameFieldWithAccessorsTest {

    @Test
    void renamesTheFieldItsAccessorsAndEveryUse() {
        var ws = Workspace.of(Map.of(
                "a/P.java", """
                        package a;

                        public class P {
                            private int qty;

                            public int getQty() {
                                return qty;
                            }

                            public void setQty(int qty) {
                                this.qty = qty;
                            }
                        }
                        """,
                "b/U.java", """
                        package b;

                        class U {
                            int twice(a.P p) {
                                p.setQty(p.getQty() * 2);
                                return p.getQty();
                            }
                        }
                        """));

        var result = ws.invoke("rename-field-with-accessors", Map.of("field", "a.P#qty", "name", "quantity"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).contains("private int quantity;", "public int getQuantity() {",
                "return quantity;", "public void setQuantity(int qty) {", "this.quantity = qty;");
        assertThat(ws.source("b/U.java")).contains("p.setQuantity(p.getQuantity() * 2);", "return p.getQuantity();");
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void worksWithoutAccessorsToo() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int qty;
                }
                """));

        assertThat(ws.invoke("rename-field-with-accessors", Map.of("field", "a.P#qty", "name", "quantity")).applied()).isTrue();
        assertThat(ws.source("a/P.java")).contains("int quantity;");
    }
}
