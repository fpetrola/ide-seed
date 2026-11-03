package ide.java.primitives;

import ide.program.Change;
import ide.program.Primitive;
import ide.projection.PrimitiveRejected;

/**
 * The primitives in Java, one class each and all of one shape: check that the primitive can be applied, edit the
 * text, carry identity along, and answer the change: the primitive's intent plus what only the text tells.
 */
public final class JavaPrimitives {

    private JavaPrimitives() {
    }

    /** What keeps two statements of a body in their order, as written, or nothing (semantics.Order). */
    public static java.util.Optional<String> ordered(ide.program.ElementId statement, ide.program.ElementId other, Context context) {
        var program = context.program();
        var a = (com.github.javaparser.ast.stmt.Statement) context.declaration(program.element(statement)).node();
        var b = (com.github.javaparser.ast.stmt.Statement) context.declaration(program.element(other)).node();
        var keeps = ide.java.semantics.Order.between(a, b, Shared.reads(context, program));
        return java.util.Optional.ofNullable(keeps).map(n -> "'" + n + "' keeps them in order");
    }

    public static Change apply(Primitive primitive, Context context) {
        var before = context.program();
        PrimitiveRejected.unlessInWorkspace(before, primitive);
        return switch (primitive) {
            case Primitive.Create p -> CreateElement.apply(p, context, before);
            case Primitive.Delete p -> DeleteElement.apply(p, context, before);
            case Primitive.Move p -> MoveElement.apply(p, context, before);
            case Primitive.Rename p -> RenameElement.apply(p, context, before);
            case Primitive.Set p -> SetAttribute.apply(p, context, before);
            case Primitive.Link p -> LinkType.apply(p, context, before);
            case Primitive.Retarget p -> RetargetUse.apply(p, context, before);
            case Primitive.Extract p -> ExtractFragment.apply(p, context, before);
            case Primitive.Inline p -> InlineCall.apply(p, context, before);
        };
    }
}
