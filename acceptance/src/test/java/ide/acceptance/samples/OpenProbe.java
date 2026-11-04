package ide.acceptance.samples;

import ide.session.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

@Tag("probe")
class OpenProbe {
    @Test
    void open() {
        var t = System.nanoTime();
        var ws = Workspace.open(Path.of(System.getProperty("probe.root")));
        System.out.println("PROBE opened ms=" + (System.nanoTime() - t) / 1_000_000 + " used=" + used());
        ws.model();
        System.out.println("PROBE read ms=" + (System.nanoTime() - t) / 1_000_000 + " used=" + used());
        histogram(System.getProperty("probe.histogram"));
        if (System.getProperty("probe.histogram") != null) return;
        var errors = ws.errors();
        var byKind = new java.util.TreeMap<String, Integer>();
        var byFile = new java.util.TreeMap<String, Integer>();
        for (var e : errors) {
            byKind.merge(e.code() + " " + e.message().replaceAll("[A-Za-z0-9_.$]+ cannot be resolved", "X cannot be resolved"), 1, Integer::sum);
            byFile.merge(e.path().replaceAll("/src/.*", ""), 1, Integer::sum);
        }
        byKind.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15)
                .forEach(k -> System.out.println("PROBE kind " + k.getValue() + " " + k.getKey()));
        byFile.forEach((f, n) -> System.out.println("PROBE where " + n + " " + f));
        errors.stream().limit(8).forEach(e -> System.out.println("PROBE sample " + e));
        System.out.println("PROBE errors=" + errors.size() + " ms=" + (System.nanoTime() - t) / 1_000_000 + " used=" + used());
    }

    static void histogram(String file) {
        if (file == null) return;
        try {
            new ProcessBuilder("jcmd", "" + ProcessHandle.current().pid(), "GC.class_histogram")
                    .redirectOutput(new java.io.File(file)).start().waitFor();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static long used() {
        System.gc();
        var rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }
}
