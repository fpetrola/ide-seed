package ide.program;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A body in no language: what a template says a method does, over elements, for each projection to write in its own.
 * It knows the method it is the body of: its parameters are values of the body, not uses between elements.
 */
class BodyTest {

    private static final ElementId PKG = new ElementId("pkg:0");
    private static final ElementId P = new ElementId("type:1");
    private static final ElementId X = new ElementId("field:2");
    private static final ElementId NEW = new ElementId("constructor:3");
    private static final ElementId SET = new ElementId("method:4");
    private static final ElementId VALUE = new ElementId("param:5");
    private static final ElementId OTHER = new ElementId("method:6");
    private static final ElementId OTHER_VALUE = new ElementId("param:7");

    /** package a; class P { int x; P(); void set(int value); void other(int value) } */
    private static Program program() {
        return new Program(List.of(
                element(PKG, Kind.PACKAGE, "a", null, new Address.Package("a")),
                element(P, Kind.CLASS, "P", PKG, new Address.Type("a.P")),
                element(X, Kind.FIELD, "x", P, new Address.Field(P, "x")),
                element(NEW, Kind.CONSTRUCTOR, "P", P, new Address.Callable(P, "new", List.of())),
                element(SET, Kind.METHOD, "set", P, new Address.Callable(P, "set", List.of("int"))),
                element(VALUE, Kind.PARAM, "value", SET, new Address.Param(SET, "value")),
                element(OTHER, Kind.METHOD, "other", P, new Address.Callable(P, "other", List.of("int"))),
                element(OTHER_VALUE, Kind.PARAM, "value", OTHER, new Address.Param(OTHER, "value"))), List.of());
    }

    private static Element element(ElementId id, Kind kind, String name, ElementId parent, Address address) {
        return new Element(id, kind, name, parent, address, id.value(), Map.of());
    }

    @Test
    void itNamesElementsByIdAndSaysWhatTheMethodDoesWithThem() {
        var text = Body.name(X) + " = " + Body.name(VALUE) + "\nreturn new " + Body.name(NEW) + "(" + Body.name(X) + ", " + Body.name(VALUE) + ")";

        var body = Body.parse(text, program(), SET);

        assertThat(text).isEqualTo("#{field:2} = #{param:5}\nreturn new #{constructor:3}(#{field:2}, #{param:5})");
        assertThat(body.statements()).containsExactly(
                new Body.Assign(X, new Body.Parameter(VALUE)),
                new Body.Return(new Body.New(NEW, List.of(new Body.Field(X), new Body.Parameter(VALUE)))));
    }

    @Test
    void itsDefaultIsWhatAMethodAnswersWhenItHasNothingToSay() {
        assertThat(Body.parse("return default", program(), SET).statements()).containsExactly(new Body.Return(new Body.Default()));
        assertThat(Body.parse("return default", program(), SET).uses()).isEmpty();
    }

    @Test
    void aCallIsOnWhatAFieldOrAParameterHoldsOrOnTheObjectItself() {
        var body = Body.parse("return #{method:6}(#{param:5}); return #{field:2}.#{method:6}(#{field:2})", program(), SET);

        assertThat(body.statements()).containsExactly(
                new Body.Return(new Body.Call(null, OTHER, List.of(new Body.Parameter(VALUE)))),
                new Body.Return(new Body.Call(new Body.Field(X), OTHER, List.of(new Body.Field(X)))));
        assertThat(body.uses()).containsExactly(new Body.Use(OTHER, Mode.CALL), new Body.Use(X, Mode.READ),
                new Body.Use(OTHER, Mode.CALL), new Body.Use(X, Mode.READ));
    }

    @Test
    void itsUsesAreOfWhatIsOutsideItsMethodInTheOrderTheyAreWritten() {
        var body = Body.parse("#{field:2} = #{param:5}\nreturn new #{constructor:3}(#{field:2})", program(), SET);

        assertThat(body.uses()).containsExactly(
                new Body.Use(X, Mode.WRITE), new Body.Use(NEW, Mode.NEW), new Body.Use(X, Mode.READ));
    }

    @Test
    void whatItNamesMustBeThereAndBeWhatItsPlaceTakes() {
        var program = program();

        assertThatThrownBy(() -> Body.parse("return #{field:9}", program, SET)).hasMessageContaining("no element field:9");
        assertThatThrownBy(() -> Body.parse("return #{method:6}", program, SET)).hasMessageContaining("expected '('");
        assertThatThrownBy(() -> Body.parse("return #{field:2}.#{field:2}()", program, SET)).hasMessageContaining("field:2 is a FIELD");
        assertThatThrownBy(() -> Body.parse("#{param:5} = #{field:2}", program, SET)).hasMessageContaining("param:5 is a PARAM");
        assertThatThrownBy(() -> Body.parse("return new #{field:2}()", program, SET)).hasMessageContaining("field:2 is a FIELD");
        assertThatThrownBy(() -> Body.parse("return #{param:7}", program, SET)).hasMessageContaining("param:7 is not a parameter of method:4");
    }

    @Test
    void anythingElseIsNotABody() {
        var program = program();

        assertThatThrownBy(() -> Body.parse("return x;", program, SET)).hasMessageContaining("expected an element");
        assertThatThrownBy(() -> Body.parse("return #{field:2} #{field:2}", program, SET)).hasMessageContaining("expected the end");
        assertThat(Body.parse("#{field:2} = #{param:5}; return #{field:2};", program, SET).statements()).hasSize(2);
        assertThatThrownBy(() -> Body.parse("this.x = 1", program, SET)).hasMessageContaining("expected an element");
    }
}
