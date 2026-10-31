package ide.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntroduceFactoryTest {

    @Test
    void everyNewGoesThroughTheFactoryAndTheConstructorBecomesPrivate() {
        var ws = Workspace.of(Map.of(
                "a/Money.java", """
                        package a;

                        public class Money {
                            private final long cents;

                            public Money(long cents) {
                                this.cents = cents;
                            }
                        }
                        """,
                "b/Shop.java", """
                        package b;

                        import a.Money;

                        class Shop {
                            Money price() {
                                return new Money(100);
                            }
                        }
                        """));

        var result = ws.invoke("introduce-factory", Map.of("constructor", "a.Money#new(long)", "name", "of"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Money.java")).isEqualTo("""
                package a;

                public class Money {
                    private final long cents;

                    private Money(long cents) {
                        this.cents = cents;
                    }

                    public static Money of(long cents) {
                        return new Money(cents);
                    }
                }
                """);
        assertThat(ws.source("b/Shop.java")).contains("return Money.of(100);");
        assertThat(ws.diagnostics()).isEmpty();
    }
}
