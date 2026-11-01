package ide.java;

import ide.projection.Projection;
import ide.projection.kit.ProjectionContract;

import java.util.Map;

/** The Java driver honors the projection contract. */
class JavaProjectionContractTest extends ProjectionContract {

    private static final Map<String, String> FIXTURE_SOURCES = Map.of(
            "a/A.java", """
                    package a;

                    public class A {
                        int x;

                        int m(int p) {
                            return x + p;
                        }

                        int k(int p) {
                            return p;
                        }

                        int total() {
                            int sum = x;
                            sum = sum + m(2);
                            return sum;
                        }
                    }
                    """,
            "a/B.java", """
                    package a;

                    public class B extends A {
                        int n() {
                            return m(1) + x;
                        }
                    }
                    """,
            "a/I.java", """
                    package a;

                    public interface I {
                    }
                    """,
            "b/C.java", """
                    package b;

                    import a.A;

                    public class C {
                        public A a;
                        public String label;

                        public int use(A other) {
                            return other.hashCode();
                        }
                    }
                    """);

    @Override
    protected Projection fixture() {
        return JavaProjection.of(FIXTURE_SOURCES);
    }

    @Override
    protected Projection reread(Projection projection) {
        return JavaProjection.of(((JavaProjection) projection).sources());
    }

    @Override
    protected String fragmentOfTotalCallingM() {
        return "16-16"; // sum = sum + m(2);
    }
}
