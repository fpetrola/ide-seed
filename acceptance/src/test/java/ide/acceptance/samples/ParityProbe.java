package ide.acceptance.samples;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Program;
import ide.session.Workspace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

/** The program read with JavaParser and with Eclipse's DOM, compared by what everything is called. */
@Tag("probe")
class ParityProbe {

    @Test
    void compare() throws Exception {
        var root = Path.of(System.getProperty("probe.root"));
        System.setProperty("ide.java.reader", "javaparser");
        var old = program(Workspace.open(root));
        System.setProperty("ide.java.reader", "dom");
        var t = System.nanoTime();
        var dom = program(Workspace.open(root));
        System.out.println("PARITY dom read ms=" + (System.nanoTime() - t) / 1_000_000);
        diff("element", elements(old), elements(dom));
        bag("reference", references(old), references(dom));
        bag("unresolved", unresolved(old), unresolved(dom));
    }

    static Program program(Workspace ws) throws Exception {
        var f = Workspace.class.getDeclaredField("project");
        f.setAccessible(true);
        return ((ide.java.JavaProjection) f.get(ws)).program();
    }

    static String q(Program p, ElementId id) {
        if (id == null) return "-";
        return p.contains(id) ? p.element(id).kind() + " " + p.element(id).qualified() : "?" + id;
    }

    static Map<String, String> elements(Program p) {
        var out = new TreeMap<String, String>();
        for (Element e : p.elements()) {
            var facts = new TreeMap<>(e.facts());
            facts.remove(ide.program.Fact.BODY); // written as is, not as JavaParser prints it
            out.put(e.kind() + " " + e.qualified(), e.name() + " parent=" + q(p, e.parent()) + " " + facts);
        }
        return out;
    }

    static String key(Program p, String id) {
        var at = id.lastIndexOf('@');
        return q(p, new ElementId(id.substring(0, at))) + " @" + id.substring(at + 1);
    }

    static Map<String, String> references(Program p) {
        var out = new TreeMap<String, String>();
        for (var r : p.references()) {
            out.put(key(p, r.id()), "-> " + q(p, r.target()) + " " + r.name() + " " + r.mode() + " " + r.receiver() + " in " + q(p, r.statement()));
        }
        return out;
    }

    static Map<String, String> unresolved(Program p) {
        var out = new TreeMap<String, String>();
        for (var u : p.unresolved()) {
            out.put(key(p, u.id()), u.name() + " " + u.mode() + " scope=" + q(p, u.scope()) + " in " + q(p, u.statement()));
        }
        return out;
    }

    /** As many of each as there are, whatever their places among their site's uses. */
    static void bag(String what, Map<String, String> old, Map<String, String> dom) {
        var a = new TreeMap<String, Integer>();
        var b = new TreeMap<String, Integer>();
        old.forEach((k, v) -> a.merge(k.substring(0, k.lastIndexOf(" @")) + " " + v, 1, Integer::sum));
        dom.forEach((k, v) -> b.merge(k.substring(0, k.lastIndexOf(" @")) + " " + v, 1, Integer::sum));
        var keys = new TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        int same = 0, onlyOld = 0, onlyDom = 0;
        var samples = new ArrayList<String>();
        var kinds = new TreeMap<String, Integer>();
        for (var k : keys) {
            var x = a.getOrDefault(k, 0);
            var y = b.getOrDefault(k, 0);
            same += Math.min(x, y);
            if (x == y) continue;
            var tag = x > y ? "only-old" : "only-dom";
            if (x > y) onlyOld += x - y; else onlyDom += y - x;
            var target = k.contains("-> ") ? k.substring(k.indexOf("-> ") + 3).split(" ")[0] : "?";
            var kind = tag + " " + target;
            if (kinds.merge(kind, Math.abs(x - y), Integer::sum) <= Integer.getInteger("probe.samples", 4)) samples.add(tag + " " + k);
        }
        System.out.printf("PARITY %s same=%d only-old=%d only-dom=%d %s%n", what, same, onlyOld, onlyDom, kinds);
        samples.forEach(s -> System.out.println("PARITY  " + s));
    }

    static void diff(String what, Map<String, String> old, Map<String, String> dom) {
        var keys = new TreeSet<>(old.keySet());
        keys.addAll(dom.keySet());
        int same = 0, onlyOld = 0, onlyDom = 0, different = 0;
        var samples = new ArrayList<String>();
        var kinds = new TreeMap<String, Integer>();
        for (var k : keys) {
            var a = old.get(k);
            var b = dom.get(k);
            if (Objects.equals(a, b)) { same++; continue; }
            String tag = a == null ? "only-dom" : b == null ? "only-old" : "different";
            if (a == null) onlyDom++; else if (b == null) onlyOld++; else different++;
            var kind = tag + " " + k.split(" ")[0];
            if (kinds.merge(kind, 1, Integer::sum) <= Integer.getInteger("probe.samples", 4)) samples.add(tag + " " + k + "\n     old: " + a + "\n     dom: " + b);
        }
        System.out.printf("PARITY %s same=%d only-old=%d only-dom=%d different=%d %s%n", what, same, onlyOld, onlyDom, different, kinds);
        samples.forEach(s -> System.out.println("PARITY  " + s));
    }
}
