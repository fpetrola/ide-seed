package ide.program;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The program as a product: a graph built by hand, no parser, answers every structural question the engine asks. */
class ProgramTest {

    private static final ElementId PKG = new ElementId("pkg:0");
    private static final ElementId A = new ElementId("type:1");
    private static final ElementId B = new ElementId("type:2");
    private static final ElementId I = new ElementId("type:3");
    private static final ElementId A_NAME = new ElementId("method:4");
    private static final ElementId B_NAME = new ElementId("method:5");
    private static final ElementId I_NAME = new ElementId("method:6");
    private static final ElementId B_X = new ElementId("field:7");
    private static final ElementId B_X_TYPE = new ElementId("type:8");

    /** package a; class A { String name() }; interface I { String name() }; class B extends A implements I { A x; String name() } */
    private static Program program() {
        var elements = List.of(
                element(PKG, Kind.PACKAGE, "a", null, "a"),
                element(A, Kind.CLASS, "A", PKG, "a.A"),
                element(I, Kind.INTERFACE, "I", PKG, "a.I"),
                element(B, Kind.CLASS, "B", PKG, "a.B"),
                element(A_NAME, Kind.METHOD, "name", A, "a.A#name()"),
                element(I_NAME, Kind.METHOD, "name", I, "a.I#name()"),
                element(B_X, Kind.FIELD, "x", B, "a.B#x"),
                element(B_NAME, Kind.METHOD, "name", B, "a.B#name()"));
        var references = List.of(
                new Reference("type:2@0", B, A, "A", Mode.EXTENDS),
                new Reference("type:2@1", B, I, "I", Mode.IMPLEMENTS),
                new Reference("field:7@0", B_X, A, "A", Mode.DECLARED),
                new Reference("method:5@0", B_NAME, B_X, "x", Mode.READ),
                new Reference("method:5@1", B_NAME, A_NAME, "name", Mode.CALL));
        return new Program(elements, references);
    }

    private static Element element(ElementId id, Kind kind, String name, ElementId parent, String qualified) {
        return new Element(id, kind, name, parent, qualified, Map.of(Fact.VISIBILITY, "package", Fact.STATIC, "false"));
    }

    @Test
    void hierarchyComesFromLinks() {
        var p = program();

        assertThat(p.supertypes(p.element(B))).extracting(Element::qualified).containsExactly("a.A", "a.I");
        assertThat(p.superclass(B)).map(Element::qualified).contains("a.A");
        assertThat(p.superclass(A)).isEmpty();
        assertThat(p.superclassChain(B)).extracting(Element::qualified).containsExactly("a.B", "a.A");
        assertThat(p.isSubclass(p.element(B), p.element(A))).isTrue();
        assertThat(p.isSubclass(p.element(A), p.element(B))).isFalse();
        assertThat(p.subclasses(A)).extracting(Element::qualified).containsExactly("a.B");
        assertThat(p.declaredType(B_X)).map(Element::qualified).contains("a.A");
    }

    @Test
    void overridingFollowsEverySupertypePath() {
        var p = program();

        assertThat(p.overriddenAll(p.element(B_NAME))).extracting(Element::qualified).containsExactly("a.A#name()", "a.I#name()");
        assertThat(p.overriddenAll(p.element(A_NAME))).isEmpty();
        assertThat(p.family(p.element(A_NAME))).extracting(Element::qualified)
                .containsExactly("a.A#name()", "a.B#name()", "a.I#name()");
    }

    @Test
    void referencesAreIndexedBothWays() {
        var p = program();

        assertThat(p.referencesTo(B_X)).extracting(Reference::id).containsExactly("method:5@0");
        assertThat(p.referencesFrom(B_NAME)).extracting(Reference::mode).containsExactly(Mode.READ, Mode.CALL);
        assertThat(p.reference("method:5@1").target()).isEqualTo(A_NAME);
        assertThat(p.find("a.B#name()")).map(Element::id).contains(B_NAME);
        assertThat(p.children(B)).extracting(Element::name).containsExactly("x", "name");
    }

    @Test
    void diffTellsWhatWasLostGainedBornAndGone() {
        var before = program();
        var ax = element(new ElementId("field:9"), Kind.FIELD, "x", A, "a.A#x");
        var elements = new java.util.ArrayList<>(before.elements());
        elements.add(ax);
        elements.removeIf(e -> e.id().equals(I_NAME));
        var references = before.references().stream()
                .map(r -> r.id().equals("method:5@0") ? new Reference(r.id(), r.site(), ax.id(), "x", Mode.READ) : r)
                .toList();
        var after = new Program(elements, references);

        var diff = before.diff(after);

        assertThat(diff.lost()).extracting(Reference::target).containsExactly(B_X);
        assertThat(diff.gained()).extracting(Reference::target).containsExactly(ax.id());
        assertThat(diff.born()).extracting(Element::id).containsExactly(ax.id());
        assertThat(diff.gone()).extracting(Element::id).containsExactly(I_NAME);
    }

    @Test
    void factsAreTyped() {
        var p = program();

        assertThat(p.element(B_X).fact(Fact.VISIBILITY)).isEqualTo("package");
        assertThat(p.element(B_X).fact(Fact.ABSTRACT)).isNull();
        assertThat(Fact.of("visibility")).isEqualTo(Fact.VISIBILITY);
        assertThat(Mode.of("update")).isEqualTo(Mode.UPDATE);
        assertThat(Mode.DECLARED.isTypeUse()).isTrue();
        assertThat(Mode.CALL.isTypeUse()).isFalse();
    }
}
