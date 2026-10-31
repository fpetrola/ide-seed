package ide.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenamePackageTest {

    @Test
    void everyTypeMovesAndImportsFollowWithoutLeavingRedundantOnes() {
        var ws = Workspace.of(Map.of(
                "a/Money.java", """
                        package a;

                        public class Money {
                            long cents;
                        }
                        """,
                "a/Shop.java", """
                        package a;

                        public class Shop {
                            Money price;

                            long cents() {
                                return price.cents;
                            }
                        }
                        """,
                "b/User.java", """
                        package b;

                        import a.Money;
                        import a.Shop;

                        class User {
                            Shop shop;
                            Money money;
                        }
                        """));

        var result = ws.invoke("rename-package", Map.of("package", "a", "name", "app.money"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.files()).containsExactlyInAnyOrder("app/money/Money.java", "app/money/Shop.java", "b/User.java");
        assertThat(ws.source("app/money/Shop.java")).isEqualTo("""
                package app.money;

                public class Shop {
                    Money price;

                    long cents() {
                        return price.cents;
                    }
                }
                """);
        assertThat(ws.source("b/User.java")).contains("import app.money.Money;", "import app.money.Shop;");
        assertThat(ws.diagnostics()).isEmpty();
    }
}
