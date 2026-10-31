package ide.refactor;

import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractInterfaceTest {

    @Test
    void createsTheInterfaceWithTheSignaturesAndTheTypeImplementsIt() {
        var ws = Workspace.of(Map.of(
                "a/Line.java", """
                        package a;

                        public class Line {
                            int qty;
                            double price;

                            public double amount() {
                                return qty * price;
                            }

                            public String describe(String prefix, int width) {
                                return prefix + width;
                            }
                        }
                        """,
                "b/Use.java", """
                        package b;

                        class Use {
                            double total(a.Line line) {
                                return line.amount();
                            }
                        }
                        """));

        var result = ws.invoke("extract-interface", Map.of("type", "a.Line", "name", "Priced",
                "methods", List.of("a.Line#amount()", "a.Line#describe(String,int)")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Priced.java")).isEqualTo("""
                package a;

                public interface Priced {

                    double amount();

                    String describe(String prefix, int width);
                }
                """);
        assertThat(ws.source("a/Line.java")).contains("public class Line implements Priced {");
        assertThat(ws.diagnostics()).isEmpty();
    }
}
