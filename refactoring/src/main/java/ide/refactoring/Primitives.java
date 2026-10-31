package ide.refactoring;

import ide.core.Param;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;

import java.util.List;
import java.util.function.BiFunction;

/** The primitives, exposed as refactorings so scripts and adapters call them like any other. */
public final class Primitives {

    private Primitives() {
    }

    public static List<Refactoring> all() {
        return List.of(
                primitive("create", "Creates an empty element", List.of(Param.of("kind", "choice", "PACKAGE", "CLASS", "INTERFACE", "FIELD", "METHOD", "PARAM"),
                        Param.of("parent", "element").optional(), Param.of("name", "text")), (ctx, args) -> {
                    var parent = args.optionalElement("parent", ctx.projection().program());
                    return ctx.projection().apply(new Primitive.Create(Kind.valueOf(args.text("kind")),
                            parent == null ? null : parent.id(), args.text("name"))).produced().value();
                }),
                primitive("move", "Moves an element into another parent", List.of(Param.of("element", "element"),
                        Param.of("to", "element"), Param.of("before", "element").optional()), (ctx, args) -> {
                    var program = ctx.projection().program();
                    var element = args.element("element", program);
                    var before = args.optionalElement("before", program);
                    ctx.projection().apply(new Primitive.Move(element.id(), args.element("to", program).id(),
                            before == null ? null : before.id()));
                    return element.id().value();
                }),
                primitive("delete", "Deletes an element nothing references", List.of(Param.of("element", "element")),
                        (ctx, args) -> {
                            ctx.projection().apply(new Primitive.Delete(args.element("element", ctx.projection().program()).id()));
                            return null;
                        }),
                primitive("rename", "Renames an element and rewrites its uses", List.of(Param.of("element", "element"),
                        Param.of("name", "text")), (ctx, args) -> {
                    var element = args.element("element", ctx.projection().program()).id();
                    ctx.projection().apply(new Primitive.Rename(element, args.text("name")));
                    return element.value();
                }),
                primitive("set", "Sets an attribute of an element", List.of(Param.of("element", "element"),
                        Param.of("attribute", "text"), Param.of("value", "text")), (ctx, args) -> {
                    var element = args.element("element", ctx.projection().program()).id();
                    ctx.projection().apply(new Primitive.Set(element, args.text("attribute"), args.text("value")));
                    return element.value();
                }),
                primitive("link", "Makes a declaration name a workspace type: extends, implements or declared",
                        List.of(Param.of("element", "element"), Param.of("mode", "choice", "EXTENDS", "IMPLEMENTS", "DECLARED"),
                                Param.of("target", "element", "CLASS", "INTERFACE", "ENUM")), (ctx, args) -> {
                    var program = ctx.projection().program();
                    var element = args.element("element", program).id();
                    ctx.projection().apply(new Primitive.Link(element, Mode.of(args.text("mode")), args.element("target", program).id()));
                    return element.value();
                }),
                primitive("extract", "Moves statements into a new method", List.of(Param.of("from", "element", "METHOD"),
                        Param.of("fragment", "text"), Param.of("into", "text")), (ctx, args) -> ctx.projection()
                        .apply(new Primitive.Extract(args.element("from", ctx.projection().program()).id(),
                                args.text("fragment"), args.text("into"))).produced().value()),
                primitive("inline", "Replaces a call by the called method's body", List.of(Param.of("call", "text")),
                        (ctx, args) -> {
                            ctx.projection().apply(new Primitive.Inline(args.text("call")));
                            return null;
                        }),
                primitive("retarget", "Points a reference at another element", List.of(Param.of("ref", "text"),
                        Param.of("target", "element")), (ctx, args) -> {
                    ctx.projection().apply(new Primitive.Retarget(args.text("ref"),
                            args.element("target", ctx.projection().program()).id()));
                    return null;
                }));
    }

    private static Refactoring primitive(String id, String description, List<Param> params,
                                         BiFunction<Ctx, Args, String> body) {
        return new Refactoring() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<Param> params() {
                return params;
            }

            @Override
            public String level() {
                return "primitive";
            }

            @Override
            public String apply(Ctx ctx, Args args) {
                return body.apply(ctx, args);
            }
        };
    }
}
