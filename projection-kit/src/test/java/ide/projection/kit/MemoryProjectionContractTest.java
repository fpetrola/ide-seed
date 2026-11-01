package ide.projection.kit;

import ide.program.Kind;
import ide.program.Mode;
import ide.projection.Needs;

import java.util.List;
import ide.projection.Projection;

/** The reference implementation honors its own contract. */
class MemoryProjectionContractTest extends ProjectionContract {

    @Override
    protected Projection fixture() {
        return MemoryProjection.builder()
                .type("a.A").field("a.A", "x", "int")
                .method("a.A", "m", "int", "int p").method("a.A", "k", "int", "int p").method("a.A", "total", "int")
                .type("a.B").extend("a.B", "a.A").method("a.B", "n", "int")
                .type(Kind.INTERFACE, "a.I")
                .library("java.lang.String", Kind.CLASS)
                .type("b.C").field("b.C", "a", "a.A").field("b.C", "label", "String").field("b.C", "price", "Money")
                .method("b.C", "use", "int", "a.A other").type("b.C.Note")
                .statement("a.A#m(int)").statement("a.A#k(int)").statement("a.A#total()").statement("a.A#total()")
                .statement("a.A#total()").statement("a.B#n()").statement("b.C#use(A)")
                .use("a.A#m(int)", "a.A#x", Mode.READ)
                .use("a.A#total()", "a.A#x", Mode.READ).use("a.A#total()", "a.A#m(int)", Mode.CALL)
                .local("a.A#total()", "sum", "int")
                .use("a.A#total()", "a.A#total()/sum", Mode.WRITE).use("a.A#total()", "a.A#total()/sum", Mode.READ)
                .use("a.A#total()", "a.A#total()/sum", Mode.READ)
                .use("a.B#n()", "a.A#m(int)", Mode.CALL).use("a.B#n()", "a.A#x", Mode.READ)
                .unresolved("b.C#price", "Money", Mode.DECLARED)
                .unresolved("b.C#use(A)", "weigh", Mode.CALL, "a.A", new Needs("int", List.of(new Needs.Argument("string", "String"))))
                .build();
    }

    @Override
    protected Projection reread(Projection projection) {
        return MemoryProjection.of(projection.program(), projection.rules());
    }

    @Override
    protected String fragmentOfTotalCallingM() {
        return "1-1"; // the second use of total(): its call to m
    }
}
