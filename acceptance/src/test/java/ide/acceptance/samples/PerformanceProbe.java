package ide.acceptance.samples;

import ide.session.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

@Tag("probe")
class PerformanceProbe {

    @Test
    void measure() {
        for (var n : new int[]{10, 40, 100}) {
            var sources = OrdersBig.generate(n);
            var t0 = System.nanoTime();
            var ws = Workspace.of(sources);
            var t1 = System.nanoTime();
            var result = ws.invoke("rename", Map.of("element", "big.Customer0#f0", "name", "g0"));
            var t2 = System.nanoTime();
            var again = ws.invoke("rename", Map.of("element", "big.Customer0#f1", "name", "g1")); // with the program read
            var t3 = System.nanoTime();
            System.out.printf("PROBE n=%d load=%dms rename=%dms applied=%s again=%dms applied=%s%n", n, (t1 - t0) / 1_000_000,
                    (t2 - t1) / 1_000_000, result.applied(), (t3 - t2) / 1_000_000, again.applied());
        }
    }
}
