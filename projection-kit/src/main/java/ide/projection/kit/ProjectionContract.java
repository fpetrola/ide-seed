package ide.projection.kit;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every projection must honor so that the products above it can rely on it. A driver's tests extend this class
 * and give the fixture in their language; {@link MemoryProjection}'s tests do the same.
 * <p>
 * The fixture is this program (its outline is checked first, so both sides agree on it):
 * <pre>
 * package a
 *   public class A           int x; int m(int p) reads x; int k(int p); int total() reads x, then calls m
 *   public class B extends A int n() calls m, then reads x
 *   public interface I
 * package b
 *   public class C           public A a; public String label; public int use(A other)
 * </pre>
 * {@code String} is the library's: the program names it, and a projection never changes it.
 */
public abstract class ProjectionContract {

    /** The fixture's outline. */
    public static final List<String> FIXTURE = List.of(
            "CLASS a.A", "CLASS a.B", "CLASS b.C", "CLASS java.lang.String (library)",
            "FIELD a.A#x", "FIELD b.C#a", "FIELD b.C#label",
            "INTERFACE a.I",
            "METHOD a.A#k(int)", "METHOD a.A#m(int)", "METHOD a.A#total()", "METHOD a.B#n()", "METHOD b.C#use(A)",
            "PARAM a.A#k(int)/p", "PARAM a.A#m(int)/p", "PARAM b.C#use(A)/other",
            "a.A#m(int) -> a.A#x (read)",
            "a.A#total() -> a.A#m(int) (call)", "a.A#total() -> a.A#x (read)",
            "a.B -> a.A (extends)",
            "a.B#n() -> a.A#m(int) (call)", "a.B#n() -> a.A#x (read)",
            "b.C#a -> a.A (declared)", "b.C#label -> java.lang.String (declared)",
            "b.C#use(A)/other -> a.A (declared)");

    /** A projection holding the fixture. */
    protected abstract Projection fixture();

    /** A new projection reading the same sources as {@code projection}: same program, fresh identity. */
    protected abstract Projection reread(Projection projection);

    /** The fragment of {@code a.A#total()} that is exactly its call to {@code a.A#m(int)}, in the driver's terms. */
    protected abstract String fragmentOfTotalCallingM();

    /** A primitive applied to the fixture in the tests below. */
    protected record Scenario(String name, Function<Program, Primitive> primitive) {
        @Override
        public String toString() {
            return name;
        }
    }

    protected List<Scenario> scenarios() {
        return List.of(
                new Scenario("rename a field", p -> new Primitive.Rename(id(p, "a.A#x"), "y")),
                new Scenario("move a method", p -> new Primitive.Move(id(p, "a.A#k(int)"), id(p, "a.B"), null)),
                new Scenario("set a visibility", p -> new Primitive.Set(id(p, "a.A#k(int)"), "visibility", "public")),
                new Scenario("link a declared type", p -> new Primitive.Link(id(p, "b.C#a"), Mode.DECLARED, id(p, "a.B"))),
                new Scenario("retarget a call", p -> new Primitive.Retarget(reference(p, "a.B#n()", "a.A#m(int)"), id(p, "a.A#k(int)"))),
                new Scenario("delete an unused method", p -> new Primitive.Delete(id(p, "a.A#k(int)"))),
                new Scenario("create a method", p -> new Primitive.Create(Kind.METHOD, id(p, "a.B"), "fresh")),
                new Scenario("add a parameter", p -> new Primitive.Create(Kind.PARAM, id(p, "a.A#m(int)"), "q",
                        Map.of(Fact.TYPE, "long"), "0")),
                new Scenario("inline a call", p -> new Primitive.Inline(reference(p, "a.B#n()", "a.A#m(int)"))),
                new Scenario("extract a statement", p -> new Primitive.Extract(id(p, "a.A#total()"), fragmentOfTotalCallingM(), "add")),
                new Scenario("rename a type", p -> new Primitive.Rename(id(p, "a.A"), "Base")),
                new Scenario("move a type", p -> new Primitive.Move(id(p, "a.I"), id(p, "b"), null)));
    }

    @Test
    void theFixtureIsTheDocumentedProgram() {
        assertThat(fixture().program().outline()).containsExactlyElementsOf(FIXTURE);
    }

    /** Obligation 6: an element of a library is never changed; using it is a reference like any other. */
    @Test
    void anElementOfALibraryIsNeverChanged() {
        var projection = fixture();
        var p = projection.program();
        var string = id(p, "java.lang.String");

        for (var primitive : List.<Primitive>of(new Primitive.Rename(string, "Text"), new Primitive.Move(string, id(p, "a"), null),
                new Primitive.Delete(string), new Primitive.Set(string, "visibility", "package"),
                new Primitive.Create(Kind.FIELD, string, "extra"), new Primitive.Move(id(p, "a.A#k(int)"), string, null))) {
            assertThatThrownBy(() -> projection.apply(primitive)).as(primitive.toString())
                    .isInstanceOfSatisfying(PrimitiveRejected.class, refused -> assertThat(refused.code()).isEqualTo("projection.library"));
        }
        assertThat(projection.program().outline()).containsExactlyElementsOf(FIXTURE);
        assertThat(p.element(string).inLibrary()).isTrue();
        assertThat(p.referencesTo(string)).extracting(r -> p.element(r.site()).qualified()).containsExactly("b.C#label");
    }

    /** Obligation 1: an element keeps its id through rename, move, set and link. */
    @Test
    void elementsKeepTheirIdsThroughRenameMoveSetAndLink() {
        var projection = fixture();
        var p = projection.program();
        var x = id(p, "a.A#x");
        var k = id(p, "a.A#k(int)");
        var a = id(p, "a.A");
        var field = id(p, "b.C#a");

        projection.apply(new Primitive.Rename(x, "y"));
        projection.apply(new Primitive.Move(k, id(p, "a.B"), null));
        projection.apply(new Primitive.Set(k, "visibility", "public"));
        projection.apply(new Primitive.Link(field, Mode.DECLARED, id(p, "a.B")));
        projection.apply(new Primitive.Rename(a, "Base"));

        var after = projection.program();
        assertThat(after.find("a.Base#y")).map(Element::id).contains(x);
        assertThat(after.find("a.B#k(int)")).map(Element::id).contains(k);
        assertThat(after.find("a.Base")).map(Element::id).contains(a);
        assertThat(after.declaredType(field)).map(Element::qualified).contains("a.B");
        assertThat(after.element(k).fact(Fact.VISIBILITY)).isEqualTo("public");
    }

    /** Obligation 1: a created element gets a new id; a deleted one is gone and its id never comes back. */
    @Test
    void createdElementsGetNewIdsAndDeletedOnesNeverComeBack() {
        var projection = fixture();
        var existing = projection.program().elements().stream().map(Element::id).collect(Collectors.toSet());

        var z = projection.apply(new Primitive.Create(Kind.FIELD, id(projection.program(), "a.B"), "z")).produced();
        assertThat(existing).doesNotContain(z);
        assertThat(projection.program().element(z).qualified()).isEqualTo("a.B#z");

        projection.apply(new Primitive.Delete(z));
        assertThat(projection.program().contains(z)).isFalse();

        var again = projection.apply(new Primitive.Create(Kind.FIELD, id(projection.program(), "a.B"), "z")).produced();
        assertThat(again).isNotEqualTo(z);
    }

    /**
     * Obligation 2: a change declares exactly what it changed on purpose. With its expectations folded into the
     * program before, nothing is lost: no binding changed that the change did not declare, and every binding it
     * declared did change.
     */
    @Test
    void eachChangeDeclaresExactlyWhatItChangedOnPurpose() {
        for (var scenario : scenarios()) {
            var projection = fixture();
            var before = projection.program();

            var change = projection.apply(scenario.primitive().apply(before));

            assertThat(before.expecting(change).diff(projection.program()).lost()).as(scenario.name()).isEmpty();
        }
    }

    /** Obligation 3: what a projection reads after a primitive is what a fresh reading of its sources gives. */
    @Test
    void readingIsReproducible() {
        for (var scenario : scenarios()) {
            var projection = fixture();

            projection.apply(scenario.primitive().apply(projection.program()));

            assertThat(projection.program().outline()).as(scenario.name())
                    .containsExactlyElementsOf(reread(projection).program().outline());
        }
    }

    /** A parameter comes with the argument its calls pass, and moves within its method. */
    @Test
    void aParameterComesWithTheArgumentItsCallsPassAndMovesWithinItsMethod() {
        var projection = fixture();
        var before = projection.program();
        var m = id(before, "a.A#m(int)");

        var q = projection.apply(new Primitive.Create(Kind.PARAM, m, "q", Map.of(Fact.TYPE, "long"), "0")).produced();
        projection.apply(new Primitive.Move(q, m, id(before, "a.A#m(int)/p")));

        var after = projection.program();
        assertThat(after.find("a.A#m(long,int)")).map(Element::id).contains(m);
        assertThat(after.children(m)).extracting(Element::name).containsExactly("q", "p");
        assertThat(before.diff(after).lost()).isEmpty();
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /** Obligation 4: restoring a snapshot gives back the same program, ids included. */
    @Test
    void restoringASnapshotReturnsToItExactly() {
        var projection = fixture();
        var before = projection.program();
        var saved = projection.snapshot();

        projection.apply(new Primitive.Rename(id(before, "a.A#x"), "y"));
        projection.apply(new Primitive.Create(Kind.METHOD, id(before, "a.B"), "fresh"));
        projection.apply(new Primitive.Delete(id(before, "a.A#k(int)")));
        projection.apply(new Primitive.Rename(id(before, "a.A"), "Base"));
        projection.restore(saved);

        assertThat(projection.program().elements()).containsExactlyElementsOf(before.elements());
        assertThat(projection.program().references()).containsExactlyElementsOf(before.references());
    }

    /** Obligation 5: a primitive that cannot be applied safely is refused and changes nothing. */
    @Test
    void aRejectedPrimitiveChangesNothing() {
        var projection = fixture();
        var before = projection.program();

        assertRejected(projection, new Primitive.Delete(id(before, "a.A#x")), "delete.referenced");
        assertRejected(projection, new Primitive.Rename(id(before, "a.A#k(int)"), "m"), "projection.duplicate");
        assertRejected(projection, new Primitive.Create(Kind.FIELD, id(before, "a.A"), "x"), "projection.duplicate");
        assertRejected(projection, new Primitive.Rename(id(before, "a.A"), "B"), "projection.duplicate");
        assertRejected(projection, new Primitive.Create(Kind.PARAM, id(before, "a.A#m(int)"), "q"), "create.argument");

        assertThat(projection.program().elements()).containsExactlyElementsOf(before.elements());
        assertThat(projection.program().references()).containsExactlyElementsOf(before.references());
    }

    private static void assertRejected(Projection projection, Primitive primitive, String code) {
        assertThatThrownBy(() -> projection.apply(primitive)).as(primitive.toString())
                .isInstanceOfSatisfying(PrimitiveRejected.class, rejected -> assertThat(rejected.code()).isEqualTo(code));
    }

    protected static ElementId id(Program program, String address) {
        return program.find(address).map(Element::id).orElseThrow(() -> new AssertionError("no element " + address));
    }

    /** The id of the first reference from {@code site} to {@code target}. */
    protected static String reference(Program program, String site, String target) {
        var from = id(program, site);
        var to = id(program, target);
        return program.referencesFrom(from).stream().filter(r -> r.target().equals(to)).map(Reference::id).findFirst()
                .orElseThrow(() -> new AssertionError("no reference from " + site + " to " + target));
    }
}
