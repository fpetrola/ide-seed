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
