package ide.program;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After an edit by hand, the program is read again: what kept its address kept its id, and what changed address is
 * new. Reconciliation tells which of the new ones are elements of before under a new name or signature.
 */
class ReconciliationTest {

    private static final ElementId PKG = new ElementId("pkg:0");

    private final List<Element> elements = new ArrayList<>();

    private ElementId add(String id, Kind kind, String name, ElementId parent, Map<Fact, String> facts) {
        var element = new ElementId(id);
        Address address = switch (kind) {
            case PACKAGE -> new Address.Package(name);
            case CLASS, INTERFACE, ENUM -> new Address.Type("a." + name);
            case FIELD -> new Address.Field(parent, name);
            case METHOD, CONSTRUCTOR -> new Address.Callable(parent, kind == Kind.CONSTRUCTOR ? "new" : name, List.of());
            case PARAM -> new Address.Param(parent, name);
            case LOCAL -> new Address.Local(parent, name, 0);
            case STATEMENT -> new Address.Statement(parent, 0);
        };
        elements.add(new Element(element, kind, name, parent, address, id, facts));
        return element;
    }

    private Program program() {
        var program = new Program(List.copyOf(elements), List.of());
        elements.clear();
        return program;
    }

    private static Map<Fact, String> typed(String type) {
        var facts = new HashMap<Fact, String>();
        facts.put(Fact.TYPE, type);
        return facts;
    }

    private static Map<Fact, String> body(String body) {
        return Map.of(Fact.BODY, body);
    }

    @Test
    void statementsArePairedByTheirOrderAndTextAndOneThatChangedIsNew() {
        var one = new ElementId("statement:1");
        var two = new ElementId("statement:2");
        var three = new ElementId("statement:3");

        var paired = Reconciliation.statements(List.of(one, two, three), List.of("a++;", "b++;", "return a;"),
                List.of("log();", "a++;", "b += 2;", "return a;"));

        assertThat(paired).containsExactly(null, one, null, three);
    }

    @Test
    void aMemberRenamedOrResignedIsTheSameMember() {
        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        var p = add("type:1", Kind.CLASS, "P", PKG, Map.of());
        var x = add("field:2", Kind.FIELD, "x", p, typed("int"));
        add("field:3", Kind.FIELD, "name", p, typed("String"));
        var k = add("method:4", Kind.METHOD, "k", p, body("{ return 1; }"));
        var m = add("method:5", Kind.METHOD, "m", p, body("{ }"));
        var before = program();

        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        add("type:1", Kind.CLASS, "P", PKG, Map.of());
        add("field:6", Kind.FIELD, "count", p, typed("int")); // x renamed
        add("field:3", Kind.FIELD, "name", p, typed("String"));
        add("method:7", Kind.METHOD, "reset", p, body("{ return 1; }")); // k renamed: same body
        add("method:8", Kind.METHOD, "m", p, body("{ log(); }")); // m re-signed: same name, new address
        var after = program();

        assertThat(Reconciliation.pairs(before, after)).containsExactlyInAnyOrderEntriesOf(Map.of(
                new ElementId("field:6"), x, new ElementId("method:7"), k, new ElementId("method:8"), m));
    }

    @Test
    void whatTheStructureDoesNotDecideStaysGoneAndNew() {
        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        var p = add("type:1", Kind.CLASS, "P", PKG, Map.of());
        add("field:2", Kind.FIELD, "a", p, typed("int"));
        add("field:3", Kind.FIELD, "b", p, typed("int"));
        add("field:4", Kind.FIELD, "gone", p, typed("long"));
        var before = program();

        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        add("type:1", Kind.CLASS, "P", PKG, Map.of());
        add("field:5", Kind.FIELD, "c", p, typed("int")); // two ints gone, two new: which is which?
        add("field:6", Kind.FIELD, "d", p, typed("int"));
        add("field:7", Kind.FIELD, "added", p, typed("String")); // a long gone, a String new: not the same
        var after = program();

        assertThat(Reconciliation.pairs(before, after)).isEmpty();
    }

    @Test
    void aTypeRenamedIsTheSameTypeAndItsMembersFollowInTheNextRound() {
        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        var p = add("type:1", Kind.CLASS, "P", PKG, Map.of());
        add("field:2", Kind.FIELD, "x", p, typed("int"));
        add("constructor:3", Kind.CONSTRUCTOR, "P", p, body("{ }"));
        var before = program();

        add("pkg:0", Kind.PACKAGE, "a", null, Map.of());
        var q = add("type:9", Kind.CLASS, "Q", PKG, Map.of()); // read anew: a new id, and new ids for what it holds
        add("field:10", Kind.FIELD, "x", q, typed("int"));
        add("constructor:11", Kind.CONSTRUCTOR, "Q", q, body("{ }"));
        var after = program();

        assertThat(Reconciliation.pairs(before, after)).containsExactlyEntriesOf(Map.of(q, p));
    }
}
