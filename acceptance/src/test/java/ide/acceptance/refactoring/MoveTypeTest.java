package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MoveTypeTest {

    @Test
    void movesTypeRewritingImportsAndOpeningWhatSamePackageAccessNeeded() {
        var ws = Workspace.of(Map.of(
                "a/Currency.java", """
                        package a;

                        class Currency {
                        }
                        """,
                "a/Money.java", """
                        package a;

                        public class Money {
                            int cents;
                            Currency currency;
                        }
                        """,
                "a/Shop.java", """
                        package a;

                        class Shop {
                            Money price;

                            int total() {
                                return price.cents;
                            }
                        }
                        """,
                "b/Other.java", """
                        package b;

                        public class Other {
                        }
                        """,
                "c/Report.java", """
                        package c;

                        import a.Money;

                        class Report {
                            Money money;
                        }
                        """));

        var result = ws.invoke("move-type", Map.of("type", "a.Money", "package", "b"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).containsExactlyInAnyOrder(
                "open-visibility b.Money#cents package -> public",
                "open-visibility a.Currency package -> public");
        assertThat(ws.files()).contains("b/Money.java").doesNotContain("a/Money.java");
        assertThat(ws.source("b/Money.java")).isEqualTo("""
                package b;

                import a.Currency;

                public class Money {
                    public int cents;
                    Currency currency;
                }
                """);
        assertThat(ws.source("a/Currency.java")).isEqualTo("""
                package a;

                public class Currency {
                }
                """);
        assertThat(ws.source("a/Shop.java")).isEqualTo("""
                package a;

                import b.Money;

                class Shop {
                    Money price;

                    int total() {
                        return price.cents;
                    }
                }
                """);
        assertThat(ws.source("c/Report.java")).isEqualTo("""
                package c;

                import b.Money;

                class Report {
                    Money money;
                }
                """);
        assertThat(ws.errors()).isEmpty();
        // protected is not even tried: who can use what says a class of another package that is not a subclass cannot
        assertThat(ws.whyRepair().lines()).containsSubsequence(
                "? access.insufficient a.Shop#total() cannot access package b.Money#cents",
                "  ! open-visibility b.Money#cents package -> public",
                "? access.insufficient b.Money#currency cannot access package a.Currency",
                "  ! open-visibility a.Currency package -> public");
    }

    @Test
    void movingIntoTheUsersPackageDropsTheirImport() {
        var ws = Workspace.of(Map.of(
                "a/Money.java", """
                        package a;

                        public class Money {
                        }
                        """,
                "c/Report.java", """
                        package c;

                        import a.Money;

                        class Report {
                            Money money;
                        }
                        """));

        ws.invoke("move-type", Map.of("type", "a.Money", "package", "c"));

        assertThat(ws.source("c/Report.java")).isEqualTo("""
                package c;

                class Report {
                    Money money;
                }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void usersThatOnlyNameTheTypeAsAReceiverGetTheImportToo() {
        var ws = Workspace.of(Map.of(
                "a/Status.java", """
                        package a;

                        public enum Status {
                            NEW, PAID
                        }
                        """,
                "a/Order.java", """
                        package a;

                        class Order {
                            boolean paid(Object s) {
                                return s == Status.PAID;
                            }
                        }
                        """,
                "b/Other.java", """
                        package b;

                        public class Other {
                        }
                        """));

        var result = ws.invoke("move-type", Map.of("type", "a.Status", "package", "b"));

        assertThat(result.violations()).isEmpty();
        // the receiver Status.PAID is a type reference too, so the projection adds the import itself
        assertThat(result.repairs()).isEmpty();
        assertThat(ws.source("a/Order.java")).contains("import b.Status;");
        assertThat(ws.errors()).isEmpty();
    }
}
