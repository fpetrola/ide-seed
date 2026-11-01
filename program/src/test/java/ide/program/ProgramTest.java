package ide.program;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        Address address = switch (kind) {
            case PACKAGE -> new Address.Package(name);
            case CLASS, INTERFACE, ENUM -> new Address.Type(qualified);
            case FIELD -> new Address.Field(parent, name);
            case METHOD, CONSTRUCTOR -> new Address.Callable(parent, name, List.of());
            case PARAM -> new Address.Param(parent, name);
        };
        return new Element(id, kind, name, parent, address, qualified, Map.of(Fact.VISIBILITY, "package", Fact.STATIC, "false"));
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

    private static final ElementId C = new ElementId("type:10");
    private static final ElementId C_NAME = new ElementId("method:11");

    /** The {@link #program()}, and class C extends B { String name() } */
    private static Program deeper() {
        var p = program();
        var elements = new ArrayList<>(p.elements());
        elements.add(element(C, Kind.CLASS, "C", PKG, "a.C"));
        elements.add(element(C_NAME, Kind.METHOD, "name", C, "a.C#name()"));
        var references = new ArrayList<>(p.references());
        references.add(new Reference("type:10@0", C, B, "B", Mode.EXTENDS));
        return new Program(elements, references);
    }

    @Test
    void theWholeHierarchyIsAQuestionToo() {
        var p = deeper();

        assertThat(p.ancestors(p.element(C))).extracting(Element::qualified).containsExactly("a.B", "a.A", "a.I");
        assertThat(p.subtypes(p.element(A))).extracting(Element::qualified).containsExactly("a.B");
        assertThat(p.subtypes(p.element(I))).extracting(Element::qualified).containsExactly("a.B");
        assertThat(p.subtypes(p.element(B))).extracting(Element::qualified).containsExactly("a.C");
        assertThat(p.overriddenTransitively(p.element(C_NAME))).extracting(Element::qualified)
                .containsExactly("a.B#name()", "a.A#name()", "a.I#name()");
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
    void aLibraryTypeIsNamedByTheProgramButIsNoPartOfTheWorkspaceHierarchy() {
        var lang = new ElementId("lib:java.lang");
        var exception = new ElementId("lib:java.lang.Exception");
        var oops = new ElementId("type:20");
        var library = Map.of(Fact.LIBRARY, "true", Fact.VISIBILITY, "public");
        var p = new Program(List.of(
                new Element(lang, Kind.PACKAGE, "java.lang", null, new Address.Package("java.lang"), "java.lang", library),
                new Element(exception, Kind.CLASS, "Exception", lang, new Address.Type("java.lang.Exception"),
                        "java.lang.Exception", library),
                element(PKG, Kind.PACKAGE, "a", null, "a"),
                element(oops, Kind.CLASS, "Oops", PKG, "a.Oops")),
                List.of(new Reference("type:20@0", oops, exception, "Exception", Mode.EXTENDS)));

        assertThat(p.element(exception).inLibrary()).isTrue();
        assertThat(p.element(oops).inLibrary()).isFalse();
        assertThat(p.referencesTo(exception)).extracting(Reference::site).containsExactly(oops);
        assertThat(p.superclass(oops)).isEmpty();
        assertThat(p.supertypes(p.element(oops))).isEmpty();
        assertThat(p.outline()).containsExactly("CLASS a.Oops", "CLASS java.lang.Exception (library)",
                "a.Oops -> java.lang.Exception (extends)");
    }

    @Test
    void anUnresolvedUseIsPartOfTheProgramUntilARetargetBindsIt() {
        var hole = new Unresolved("field:7@1", B_X, "Money", Mode.TYPE);
        var p = new Program(program().elements(), program().references(), List.of(hole));

        assertThat(p.unresolved()).containsExactly(hole);
        assertThat(p.unresolved("field:7@1")).contains(hole);
        assertThat(p.outline()).contains("a.B#x -> ? Money (type)");

        var change = new Primitive.Retarget("field:7@1", A).intent(p, null);

        assertThat(change.expectations()).containsExactly(new Change.Bound("field:7@1", A));
        var expected = p.expecting(change);
        assertThat(expected.unresolved()).isEmpty();
        assertThat(expected.referencesFrom(B_X)).extracting(Reference::target).containsExactly(A, A);
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
        assertThat(ax.address()).isEqualTo(new Address.Field(A, "x"));
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
    void expectationsTurnABaselineIntoWhatTheVerifierShouldExpect() {
        var before = program();

        assertThat(before.expecting(new Change.Retargeted(B_NAME, A_NAME, I_NAME)).referencesFrom(B_NAME))
                .extracting(Reference::target).containsExactly(B_X, I_NAME);
        assertThat(before.expecting(new Change.Resited(B_X, B_NAME, A_NAME)).referencesFrom(A_NAME))
                .extracting(Reference::target).containsExactly(B_X);
        assertThat(before.expecting(new Change.Dropped(B_NAME, A_NAME)).referencesFrom(B_NAME))
                .extracting(Reference::target).containsExactly(B_X);
        assertThat(before.expecting(new Change.UsesGone(B_NAME)).references())
                .extracting(Reference::site).containsOnly(B, B_X);

        var change = Change.NONE.expect(new Change.Dropped(B_NAME, A_NAME)).expect(new Change.UsesGone(B_X))
                .pin(B_X, Change.Aspect.NAME);
        assertThat(before.expecting(change).references()).hasSize(3);
        assertThat(change.pins()).containsExactly(new Change.Pin(B_X, Change.Aspect.NAME));
        assertThat(Change.produced(B_X).produced()).isEqualTo(B_X);
    }

    @Test
    void eachPrimitiveSaysWhatItMeansToChange() {
        var p = program();
        var created = new ElementId("field:9");

        assertThat(new Primitive.Retarget("method:5@1", I_NAME).intent(p, null).expectations())
                .containsExactly(new Change.Retargeted(B_NAME, A_NAME, I_NAME));
        assertThat(new Primitive.Inline("method:5@1").intent(p, null).expectations())
                .containsExactly(new Change.Dropped(B_NAME, A_NAME));
        assertThat(new Primitive.Delete(B_X).intent(p, null).expectations()).containsExactly(new Change.UsesGone(B_X));
        assertThat(new Primitive.Rename(B_X, "y").intent(p, null).pins()).containsExactly(new Change.Pin(B_X, Change.Aspect.NAME));
        assertThat(new Primitive.Move(B_X, A, null).intent(p, null).pins()).containsExactly(new Change.Pin(B_X, Change.Aspect.PARENT));
        assertThat(new Primitive.Set(B_X, "visibility", "private").intent(p, null).pins())
                .containsExactly(new Change.Pin(B_X, Change.Aspect.VISIBILITY));
        assertThat(new Primitive.Set(B_X, "static", "true").intent(p, null)).isEqualTo(Change.NONE);
        assertThat(new Primitive.Create(Kind.FIELD, A, "y").intent(p, created)).isEqualTo(Change.produced(created)
                .pin(created, Change.Aspect.PARENT, Change.Aspect.NAME));
        assertThat(new Primitive.Create(Kind.PACKAGE, null, "b").intent(p, created)).isEqualTo(Change.produced(created));
        assertThat(new Primitive.Create(Kind.METHOD, A, "get", Map.of(Fact.VISIBILITY, "public"), null).intent(p, created).pins())
                .containsExactly(new Change.Pin(created, Change.Aspect.PARENT), new Change.Pin(created, Change.Aspect.NAME),
                        new Change.Pin(created, Change.Aspect.VISIBILITY));
    }

    @Test
    void aLinkExpectsTheDeclarationToBindItsNewTarget() {
        var p = program();

        assertThat(new Primitive.Link(B, Mode.EXTENDS, I).intent(p, null).expectations())
                .containsExactly(new Change.Retargeted(B, A, I));
        assertThat(new Primitive.Link(B_X, Mode.DECLARED, B).intent(p, null).expectations())
                .containsExactly(new Change.Retargeted(B_X, A, B));
        assertThat(new Primitive.Link(A, Mode.EXTENDS, B).intent(p, null)).isEqualTo(Change.NONE); // A extended nothing
        assertThat(new Primitive.Link(A, Mode.IMPLEMENTS, I).intent(p, null)).isEqualTo(Change.NONE); // one more interface
        assertThat(new Primitive.Link(B, Mode.EXTENDS, A).intent(p, null)).isEqualTo(Change.NONE); // already so
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Primitive.Link(B, Mode.CALL, A))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outlineIsTheProgramWithoutIds() {
        assertThat(program().outline()).containsExactly(
                "CLASS a.A", "CLASS a.B", "FIELD a.B#x", "INTERFACE a.I",
                "METHOD a.A#name()", "METHOD a.B#name()", "METHOD a.I#name()",
                "a.B -> a.A (extends)", "a.B -> a.I (implements)",
                "a.B#name() -> a.A#name() (call)", "a.B#name() -> a.B#x (read)", "a.B#x -> a.A (declared)");
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
