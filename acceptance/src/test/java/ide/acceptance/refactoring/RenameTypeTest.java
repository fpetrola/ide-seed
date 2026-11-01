package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenameTypeTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/Money.java", """
                    package a;

                    public class Money {
                        private final long cents;

                        public Money(long cents) {
                            this.cents = cents;
                        }

                        public static Money zero() {
                            return new Money(0);
                        }
                    }
                    """,
            "b/Shop.java", """
                    package b;

                    import a.Money;

                    class Shop {
                        Money price = new Money(100);
                        Money free = Money.zero();
                    }
                    """,
            "a/Wallet.java", """
                    package a;

                    class Wallet {
                        java.util.List<Money> coins;
                    }
                    """);

    @Test
    void renamesTheTypeItsConstructorsFileImportsAndEveryUse() {
        var ws = Workspace.of(SOURCES);

        var result = ws.invoke("rename", Map.of("element", "a.Money", "name", "Amount"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.files()).contains("a/Amount.java").doesNotContain("a/Money.java");
        assertThat(ws.source("a/Amount.java")).isEqualTo("""
                package a;

                public class Amount {
                    private final long cents;

                    public Amount(long cents) {
                        this.cents = cents;
                    }

                    public static Amount zero() {
                        return new Amount(0);
                    }
                }
                """);
        assertThat(ws.source("b/Shop.java")).isEqualTo("""
                package b;

                import a.Amount;

                class Shop {
                    Amount price = new Amount(100);
                    Amount free = Amount.zero();
                }
                """);
        assertThat(ws.source("a/Wallet.java")).contains("java.util.List<Amount> coins;");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void renamesATypeUsedAsAParameterTypeElsewhere() {
        var ws = Workspace.of(Map.of(
                "a/Money.java", """
                        package a;

                        public class Money {
                            public long cents;
                        }
                        """,
                "b/Shop.java", """
                        package b;

                        import a.Money;

                        class Shop {
                            long pay(Money m) {
                                return m.cents;
                            }
                        }
                        """));

        var result = ws.invoke("rename", Map.of("element", "a.Money", "name", "Amount"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("b/Shop.java")).contains("import a.Amount;", "long pay(Amount m) {");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void renamingOntoAnExistingTypeIsRefused() {
        var ws = Workspace.of(SOURCES);

        var result = ws.invoke("rename", Map.of("element", "a.Money", "name", "Wallet"));

        assertThat(result.violations()).extracting(Violation::code).containsExactly("projection.duplicate");
    }

    @Test
    void aNameThatWouldCaptureTheUsesOfALibraryTypeIsCaught() {
        var ws = Workspace.of(Map.of(
                "a/Item.java", "package a;\n\npublic class Item {\n}\n",
                "a/Report.java", "package a;\n\nimport java.util.*;\n\nclass Report {\n    List lines;\n}\n"));

        // a type of the same package shadows an on-demand import: Report's List would quietly become a.List
        var result = ws.invoke("rename", Map.of("element", "a.Item", "name", "List"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.rebound");
        assertThat(ws.source("a/Item.java")).contains("public class Item");
    }
}
