package ide.samples;

import java.util.Map;
import java.util.TreeMap;

/** Generates a project of {@code n} customer classes that call each other: ~24 elements and ~100 references each. */
final class OrdersBig {

    private OrdersBig() {
    }

    static Map<String, String> generate(int n) {
        var sources = new TreeMap<String, String>();
        sources.put("big/Base.java", """
                package big;

                public abstract class Base {
                    protected long id;

                    public long id() {
                        return id;
                    }

                    public abstract double score();
                }
                """);
        for (var i = 0; i < n; i++) {
            var next = (i + 1) % n;
            var code = new StringBuilder();
            code.append("package big;\n\npublic class Customer").append(i).append(" extends Base {\n");
            for (var f = 0; f < 6; f++) code.append("    public int f").append(f).append(";\n");
            code.append("    Customer").append(next).append(" next;\n\n");
            for (var m = 0; m < 12; m++) {
                code.append("    public int m").append(m).append("(int v) {\n");
                code.append("        int s = v + f").append(m % 6).append(" + f").append((m + 1) % 6).append(";\n");
                code.append("        if (next != null) s += next.f").append(m % 6).append(" + next.m").append((m + 3) % 12).append("(0) * 0;\n");
                code.append("        return s + (int) id();\n    }\n\n");
            }
            code.append("    @Override\n    public double score() {\n        return m0(1) + f0;\n    }\n}\n");
            sources.put("big/Customer" + i + ".java", code.toString());
        }
        return sources;
    }
}
