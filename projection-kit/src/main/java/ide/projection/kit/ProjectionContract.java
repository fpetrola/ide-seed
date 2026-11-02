package ide.projection.kit;

import ide.program.Body;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.program.Unresolved;
import ide.projection.Needs;
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
 *   public class A           int x; int m(int p) reads x; int k(int p); int total() reads x into its local sum,
 *                            adds what m answers to it, and answers it
 *   public class B extends A int n() calls m, then reads x
 *   public interface I
 * package b
 *   public class C           public A a; public String label; public Money price; public int use(A other) answers
 *                            other.weigh("kg"); public static class Note, which nothing uses
 * </pre>
 * {@code String} is the library's: the program names it, and a projection never changes it. {@code Money} is nowhere:
 * the use binds nothing, until a retarget binds it. Nor is {@code weigh}: a call looked up in {@code a.A}, passing a
 * {@code String} where an {@code int} is expected.
 */
public abstract class ProjectionContract {

    /** The fixture's outline. */
    public static final List<String> FIXTURE = List.of(
            "CLASS a.A", "CLASS a.B", "CLASS b.C", "CLASS b.C.Note", "CLASS java.lang.String (library)",
            "FIELD a.A#x", "FIELD b.C#a", "FIELD b.C#label", "FIELD b.C#price",
            "INTERFACE a.I",
            "LOCAL a.A#total()/sum",
            "METHOD a.A#k(int)", "METHOD a.A#m(int)", "METHOD a.A#total()", "METHOD a.B#n()", "METHOD b.C#use(A)",
            "PARAM a.A#k(int)/p", "PARAM a.A#m(int)/p", "PARAM b.C#use(A)/other",
            "STATEMENT a.A#k(int)[1]", "STATEMENT a.A#m(int)[1]",
            "STATEMENT a.A#total()[1]", "STATEMENT a.A#total()[2]", "STATEMENT a.A#total()[3]",
            "STATEMENT a.B#n()[1]", "STATEMENT b.C#use(A)[1]",
            "a.A#m(int) -> a.A#x (read) in [1]",
            "a.A#total() -> a.A#m(int) (call) in [2]", "a.A#total() -> a.A#total()/sum (read) in [2]",
            "a.A#total() -> a.A#total()/sum (read) in [3]", "a.A#total() -> a.A#total()/sum (write) in [2]",
            "a.A#total() -> a.A#x (read) in [1]",
            "a.B -> a.A (extends)",
            "a.B#n() -> a.A#m(int) (call) in [1]", "a.B#n() -> a.A#x (read) in [1]",
            "b.C#a -> a.A (declared)", "b.C#label -> java.lang.String (declared)", "b.C#price -> ? Money (declared)",
            "b.C#use(A) -> ? weigh (call) in [1]",
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
                new Scenario("rename a local", p -> new Primitive.Rename(id(p, "a.A#total()/sum"), "s")),
                new Scenario("move a method", p -> new Primitive.Move(id(p, "a.A#k(int)"), id(p, "a.B"), null)),
                new Scenario("set a visibility", p -> new Primitive.Set(id(p, "a.A#k(int)"), "visibility", "public")),
                new Scenario("declare what a method throws", p -> new Primitive.Set(id(p, "a.A#k(int)"), "throws", "java.io.IOException")),
                new Scenario("link a declared type", p -> new Primitive.Link(id(p, "b.C#a"), Mode.DECLARED, id(p, "a.B"))),
                new Scenario("retarget a call", p -> new Primitive.Retarget(reference(p, "a.B#n()", "a.A#m(int)"), id(p, "a.A#k(int)"))),
                new Scenario("retarget a parameter's type", p -> new Primitive.Retarget(reference(p, "b.C#use(A)/other", "a.A"), id(p, "a.B"))),
                new Scenario("delete an unused method", p -> new Primitive.Delete(id(p, "a.A#k(int)"))),
                new Scenario("delete a nested type", p -> new Primitive.Delete(id(p, "b.C.Note"))),
                new Scenario("delete a method into another", p -> new Primitive.Delete(id(p, "a.A#m(int)"), id(p, "a.A#k(int)"))),
                new Scenario("create a method", p -> new Primitive.Create(Kind.METHOD, id(p, "a.B"), "fresh")),
                new Scenario("add a parameter", p -> new Primitive.Create(Kind.PARAM, id(p, "a.A#m(int)"), "q",
                        Map.of(Fact.TYPE, "long"), "0")),
                new Scenario("inline a call", p -> new Primitive.Inline(reference(p, "a.B#n()", "a.A#m(int)"))),
                new Scenario("extract a statement", p -> new Primitive.Extract(id(p, "a.A#total()"), fragmentOfTotalCallingM(), "add")),
                new Scenario("extract statements by their ids", p -> new Primitive.Extract(id(p, "a.A#total()"),
                        id(p, "a.A#total()[2]").value() + "," + id(p, "a.A#total()[3]").value(), "finish")),
                new Scenario("create a statement", p -> new Primitive.Create(Kind.STATEMENT, id(p, "a.A#k(int)"), "",
                        Map.of(Fact.BODY, "return " + Body.name(id(p, "a.A#x"))), null)),
                new Scenario("move a statement into another method", p -> new Primitive.Move(id(p, "a.B#n()[1]"), id(p, "a.A#k(int)"),
                        id(p, "a.A#k(int)[1]"))),
                new Scenario("delete a statement", p -> new Primitive.Delete(id(p, "a.A#total()[2]"))),
                new Scenario("rename a type", p -> new Primitive.Rename(id(p, "a.A"), "Base")),
                new Scenario("move a type", p -> new Primitive.Move(id(p, "a.I"), id(p, "b"), null)),
                new Scenario("bind an unresolved use", p -> new Primitive.Retarget(unresolved(p, "b.C#price"), id(p, "a.A"))));
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

    /** Obligation 7: retargeting a use that binds nothing binds it: it is a reference from then on. */
    @Test
    void retargetingAnUnresolvedUseBindsIt() {
        var projection = fixture();
        var p = projection.program();
        var price = id(p, "b.C#price");
        var use = unresolved(p, "b.C#price");

        var change = projection.apply(new Primitive.Retarget(use, id(p, "a.A")));

        var after = projection.program();
        assertThat(change.expectations()).containsExactly(new Change.Bound(use, id(p, "a.A")));
        assertThat(after.unresolved()).extracting(Unresolved::name).containsExactly("weigh");
        assertThat(after.declaredType(price)).map(Element::qualified).contains("a.A");
    }

    /**
     * Obligation 8: a statement is said in no language ({@link Body}); the projection writes it in its own, and the
     * method makes the uses the statements name, in the order they name them. Its parameters are values, not uses.
     */
    @Test
    void aStatementSaidInNoLanguageMakesTheUsesItNames() {
        var projection = fixture();
        var p = projection.program();
        var k = id(p, "a.A#k(int)");
        var x = Body.name(id(p, "a.A#x"));

        projection.apply(new Primitive.Create(Kind.STATEMENT, k, "", Map.of(Fact.BODY, x + " = " + Body.name(id(p, "a.A#k(int)/p"))), null));
        projection.apply(new Primitive.Create(Kind.STATEMENT, k, "", Map.of(Fact.BODY, "return " + x), null));

        var after = projection.program();
        assertThat(after.referencesFrom(k)).extracting(r -> after.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactly("a.A#x write", "a.A#x read");
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /** Obligation 8 too: a constructor may have its object made first by its superclass's constructor. */
    @Test
    void aConstructorMayHaveItsObjectMadeByTheSuperclasssConstructor() {
        var projection = fixture();
        var base = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(projection.program(), "a.A"), "A")).produced();
        var made = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(projection.program(), "a.B"), "B")).produced();

        var change = projection.apply(new Primitive.Create(Kind.STATEMENT, made, "", Map.of(Fact.BODY, Body.name(base) + "()"), null));

        var after = projection.program();
        assertThat(change.expectations()).containsExactly(new Change.Added(made, base, "A", Mode.CALL));
        assertThat(after.referencesFrom(made)).extracting(r -> after.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactly("a.A#new() call");
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /**
     * Obligation 14: the statements of a body are elements of its method or constructor, in the order they are written;
     * what one holds, a block too, is part of it, and a use made in it says so. A statement created at the end of a
     * body leaves the method's own as they were.
     */
    @Test
    void theStatementsOfABodyAreElementsOfItsMethodInOrder() {
        var projection = fixture();
        var p = projection.program();
        var m = id(p, "a.A#m(int)");
        var total = id(p, "a.A#total()");
        var own = id(p, "a.A#m(int)[1]");
        assertThat(p.referencesIn(id(p, "a.A#total()[2]"))).extracting(r -> p.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactlyInAnyOrder("a.A#total()/sum write", "a.A#total()/sum read", "a.A#m(int) call");
        assertThat(p.referencesIn(id(p, "a.A#total()[2]"))).allMatch(r -> r.site().equals(total));

        projection.apply(new Primitive.Create(Kind.STATEMENT, m, "", Map.of(Fact.BODY, Body.name(id(p, "a.A#x")) + " = "
                + Body.name(id(p, "a.A#m(int)/p"))), null));

        var after = projection.program();
        assertThat(p.children(total)).filteredOn(e -> e.kind() == Kind.STATEMENT).extracting(Element::qualified)
                .containsExactly("a.A#total()[1]", "a.A#total()[2]", "a.A#total()[3]");
        assertThat(after.children(m)).filteredOn(e -> e.kind() == Kind.STATEMENT).extracting(Element::id).hasSize(2).startsWith(own);
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /**
     * Obligation 14 too: a fragment that names statements of a body moves them into the new method, where they keep who
     * they are and the uses made in them are the new method's; a statement calling it takes their place.
     */
    @Test
    void aFragmentNamingStatementsMovesThemIntoTheNewMethod() {
        var projection = fixture();
        var p = projection.program();
        var total = id(p, "a.A#total()");
        var second = id(p, "a.A#total()[2]");

        var change = projection.apply(new Primitive.Extract(total, second.value(), "add"));

        var after = projection.program();
        var added = change.produced();
        assertThat(after.element(second).parent()).isEqualTo(added);
        assertThat(after.referencesIn(second)).isNotEmpty().allMatch(r -> r.site().equals(added));
        var own = after.children(total).stream().filter(e -> e.kind() == Kind.STATEMENT).toList();
        assertThat(own).hasSize(3).doesNotContain(after.element(second));
        assertThat(after.referencesIn(own.get(1).id())).anyMatch(r -> r.target().equals(added) && r.mode() == Mode.CALL);
        assertThat(p.expecting(change).diff(after).lost()).isEmpty();
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /**
     * Obligation 14 too: a statement is created said in no language at the end of a body, making the uses it names; it
     * moves before another, or into another method with the uses made in it, and stays who it is; and it goes with them.
     */
    @Test
    void aStatementIsCreatedMovedAndDeletedWithTheUsesMadeInIt() {
        var projection = fixture();
        var p = projection.program();
        var k = id(p, "a.A#k(int)");
        var n = id(p, "a.B#n()");
        var own = id(p, "a.A#k(int)[1]");

        var created = projection.apply(new Primitive.Create(Kind.STATEMENT, k, "", Map.of(Fact.BODY, "return " + Body.name(id(p, "a.A#x"))), null))
                .produced();
        projection.apply(new Primitive.Move(created, k, own));

        var placed = projection.program();
        assertThat(placed.children(k)).filteredOn(e -> e.kind() == Kind.STATEMENT).extracting(Element::id).containsExactly(created, own);
        assertThat(placed.referencesIn(created)).extracting(r -> placed.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactly("a.A#x read");
        assertThat(placed.outline()).containsExactlyElementsOf(reread(projection).program().outline());

        var moved = projection.apply(new Primitive.Move(created, n, null));

        var away = projection.program();
        assertThat(away.children(n)).filteredOn(e -> e.kind() == Kind.STATEMENT).extracting(Element::id).endsWith(created);
        assertThat(away.referencesIn(created)).extracting(r -> r.site()).containsExactly(n);
        assertThat(placed.expecting(moved).diff(away).lost()).isEmpty();

        projection.apply(new Primitive.Delete(created));

        var gone = projection.program();
        assertThat(gone.contains(created)).isFalse();
        assertThat(gone.children(n)).filteredOn(e -> e.kind() == Kind.STATEMENT).hasSize(1);
        assertThat(gone.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

    /** Obligation 14 too: a statement that calls a constructor is created first in its body, since that call comes first. */
    @Test
    void aStatementCallingAConstructorIsCreatedFirst() {
        var projection = fixture();
        var base = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(projection.program(), "a.A"), "A")).produced();
        var made = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(projection.program(), "a.B"), "B")).produced();
        var p = projection.program();
        var gives = projection.apply(new Primitive.Create(Kind.STATEMENT, made, "", Map.of(Fact.BODY, Body.name(id(p, "a.A#x")) + " = default"), null))
                .produced();

        var calls = projection.apply(new Primitive.Create(Kind.STATEMENT, made, "", Map.of(Fact.BODY, Body.name(base) + "()"), null)).produced();

        var after = projection.program();
        assertThat(after.children(made)).filteredOn(e -> e.kind() == Kind.STATEMENT).extracting(Element::id).containsExactly(calls, gives);
        assertThat(after.outline()).containsExactlyElementsOf(reread(projection).program().outline());
    }

@Test
    void aFieldsStartSaidInNoLanguageMakesTheUsesItNames() {
        var projection = fixture();
        var p = projection.program();
        var label = id(p, "b.C#label");
        assertThat(p.element(label).fact(Fact.INITIALIZED)).isNotEqualTo("true");

        var change = projection.apply(new Primitive.Set(label, "initializer", Body.name(id(p, "b.C#a"))));

        var after = projection.program();
        assertThat(after.referencesFrom(label)).filteredOn(r -> !r.mode().isLink())
                .extracting(r -> after.element(r.target()).qualified() + " " + r.mode().label()).containsExactly("b.C#a read");
        assertThat(change.expectations()).contains(new Change.Added(label, id(p, "b.C#a"), "a", Mode.READ));
        assertThat(after.element(label).fact(Fact.INITIALIZED)).isEqualTo("true");
    }

    /**
     * Obligation 9: where a projection says an element is declared, the element there is that one, and the one its
     * name is inside. A projection with no text says nothing is anywhere.
     */
    @Test
    void whereAnElementIsDeclaredIsWhereItIsFound() {
        var projection = fixture();
        var p = projection.program();

        for (var element : p.elements()) {
            projection.locate(element.id()).ifPresent(at -> {
                assertThat(projection.elementAt(at.path(), at.line(), at.column())).as(element.qualified() + " at " + at).contains(element.id());
                assertThat(projection.inside(at.path(), at.line(), at.column())).as("inside " + element.qualified()).contains(element.id());
            });
        }
        assertThat(projection.locate(id(p, "java.lang.String"))).isEmpty();
    }

    /**
     * Obligation 10: a use of a member that binds nothing says the type it is looked up in, and the projection says,
     * when asked, what it needs of what would bind it, as the language writes types; of anything else, nothing.
     */
    @Test
    void aUseThatBindsNothingSaysWhereItIsLookedUpAndWhatItNeeds() {
        var projection = fixture();
        var p = projection.program();
        var weigh = p.unresolved().stream().filter(u -> u.name().equals("weigh")).findFirst().orElseThrow();

        assertThat(weigh.mode()).isEqualTo(Mode.CALL);
        assertThat(p.element(weigh.scope()).qualified()).isEqualTo("a.A");
        assertThat(projection.needs(weigh.id())).contains(new Needs("int", List.of(new Needs.Argument("string", "String"))));
        assertThat(projection.needs(reference(p, "a.B#n()", "a.A#m(int)"))).isEmpty();
    }

    /**
     * Obligation 15: a method or constructor says what it declares it throws, as written ({@code THROWS}, nothing if it
     * declares nothing); setting it declares those types, each written as its file can name it, in that order.
     */
    @Test
    void aCallableSaysWhatItDeclaresItThrowsAndSettingItDeclaresThem() {
        var projection = fixture();
        var k = id(projection.program(), "a.A#k(int)");
        assertThat(projection.program().element(k).fact(Fact.THROWS)).isNull();

        projection.apply(new Primitive.Set(k, "throws", "java.io.IOException, IllegalStateException"));

        assertThat(projection.program().element(k).fact(Fact.THROWS)).matches("(java\\.io\\.)?IOException, IllegalStateException");
        assertThat(projection.program().element(k).qualified()).isEqualTo("a.A#k(int)");
    }

    /**
     * Obligation 12: a use says what it is made on. The fixture's uses of members are all on the object itself
     * (written alone); a link, a type used or a local is made on nothing.
     */
    @Test
    void aUseSaysWhatItIsMadeOn() {
        var p = fixture().program();

        assertThat(p.referencesFrom(id(p, "a.B#n()"))).extracting(r -> r.name() + " " + r.receiver())
                .containsExactly("m SELF", "x SELF");
        assertThat(p.referencesFrom(id(p, "a.A#total()"))).filteredOn(r -> p.element(r.target()).kind() != Kind.LOCAL)
                .extracting(Reference::receiver).containsOnly(Reference.Receiver.SELF);
        assertThat(p.referencesFrom(id(p, "a.B"))).extracting(Reference::receiver).containsOnly(Reference.Receiver.NONE);
        assertThat(p.referencesFrom(id(p, "b.C#a"))).extracting(Reference::receiver).containsOnly(Reference.Receiver.NONE);
    }

    /**
     * Obligation 13: a local is an element of its method, with its type; its uses are references from the method,
     * made on nothing, in the order they are written.
     */
    @Test
    void aLocalIsAnElementOfItsMethodAndItsUsesReferencesFromIt() {
        var p = fixture().program();
        var sum = p.find("a.A#total()/sum").orElseThrow();

        assertThat(sum.kind()).isEqualTo(Kind.LOCAL);
        assertThat(sum.parent()).isEqualTo(id(p, "a.A#total()"));
        assertThat(sum.fact(Fact.TYPE)).isEqualTo("int");
        assertThat(p.referencesFrom(id(p, "a.A#total()"))).filteredOn(r -> r.target().equals(sum.id()))
                .extracting(r -> r.mode().label() + " " + r.receiver()).containsExactly("write NONE", "read NONE", "read NONE");
    }

    /**
     * Obligation 11: a statement says what it does in no language ({@link Body}) when all of it can be said so; nothing
     * when something in it cannot (a local, an operator), and then it is opaque. Statements created say themselves back.
     */
    @Test
    void aStatementSaysWhatItDoesWhenItCan() {
        var projection = fixture();
        var p = projection.program();

        assertThat(projection.said(id(p, "a.A#k(int)[1]"))).contains("return " + Body.name(id(p, "a.A#k(int)/p")));
        assertThat(projection.said(id(p, "a.A#total()[1]"))).isEmpty();
        assertThat(projection.said(id(p, "a.A#m(int)[1]"))).isEmpty();
        assertThat(projection.said(id(p, "a.A#k(int)"))).isEmpty();
        var k = id(p, "a.A#k(int)");
        var x = Body.name(id(p, "a.A#x"));
        var written = List.of(x + " = " + Body.name(id(p, "a.A#k(int)/p")), "return " + x);
        for (var said : written) projection.apply(new Primitive.Create(Kind.STATEMENT, k, "", Map.of(Fact.BODY, said), null));
        var rewritten = projection.program();
        assertThat(rewritten.children(k).stream().filter(e -> e.kind() == Kind.STATEMENT).skip(1).map(e -> projection.said(e.id()).orElse("?")))
                .containsExactlyElementsOf(written);
        var base = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(p, "a.A"), "A")).produced();
        var made = projection.apply(new Primitive.Create(Kind.CONSTRUCTOR, id(projection.program(), "a.B"), "B")).produced();
        var first = projection.apply(new Primitive.Create(Kind.STATEMENT, made, "", Map.of(Fact.BODY, Body.name(base) + "()"), null)).produced();
        assertThat(projection.said(first)).contains(Body.name(base) + "()");
    }

    /**
     * Obligation 1: an element keeps its id through rename, move, set, link and retarget, also when what it does
     * changes its address: a method whose parameter is declared with another type is the same method.
     */
    @Test
    void elementsKeepTheirIdsThroughRenameMoveSetLinkAndRetarget() {
        var projection = fixture();
        var p = projection.program();
        var x = id(p, "a.A#x");
        var k = id(p, "a.A#k(int)");
        var a = id(p, "a.A");
        var field = id(p, "b.C#a");
        var use = id(p, "b.C#use(A)");

        projection.apply(new Primitive.Rename(x, "y"));
        projection.apply(new Primitive.Move(k, id(p, "a.B"), null));
        projection.apply(new Primitive.Set(k, "visibility", "public"));
        projection.apply(new Primitive.Link(field, Mode.DECLARED, id(p, "a.B")));
        projection.apply(new Primitive.Retarget(reference(p, "b.C#use(A)/other", "a.A"), id(p, "a.B")));
        projection.apply(new Primitive.Rename(a, "Base"));

        var after = projection.program();
        assertThat(after.find("a.Base#y")).map(Element::id).contains(x);
        assertThat(after.find("a.B#k(int)")).map(Element::id).contains(k);
        assertThat(after.find("a.Base")).map(Element::id).contains(a);
        assertThat(after.find("b.C#use(B)")).map(Element::id).contains(use);
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
        assertThat(after.children(m)).filteredOn(e -> e.kind() == Kind.PARAM).extracting(Element::name).containsExactly("q", "p");
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
        assertThat(projection.program().unresolved()).containsExactlyElementsOf(before.unresolved());
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
        assertRejected(projection, new Primitive.Delete(id(before, "a.A#x"), id(before, "a.A#k(int)")), "delete.into");

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

    /** The id of the first use from {@code site} that binds nothing. */
    protected static String unresolved(Program program, String site) {
        var from = id(program, site);
        return program.unresolved().stream().filter(u -> u.site().equals(from)).map(Unresolved::id).findFirst()
                .orElseThrow(() -> new AssertionError("no unresolved use from " + site));
    }

    /** The id of the first reference from {@code site} to {@code target}. */
    protected static String reference(Program program, String site, String target) {
        var from = id(program, site);
        var to = id(program, target);
        return program.referencesFrom(from).stream().filter(r -> r.target().equals(to)).map(Reference::id).findFirst()
                .orElseThrow(() -> new AssertionError("no reference from " + site + " to " + target));
    }
}
