package ide.refactoring;

import ide.core.Param;
import ide.program.Kind;

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
                    var graph = ctx.projection().graph();
                    var parent = args.optionalElement("parent", graph);
                    return ctx.projection().create(Kind.valueOf(args.text("kind")), parent == null ? null : parent.id(),
                            args.text("name")).value();
                }),
                primitive("move", "Moves an element into another parent", List.of(Param.of("element", "element"),
                        Param.of("to", "element"), Param.of("before", "element").optional()), (ctx, args) -> {
                    var graph = ctx.projection().graph();
                    var element = args.element("element", graph);
                    var before = args.optionalElement("before", graph);
                    ctx.projection().move(element.id(), args.element("to", graph).id(), before == null ? null : before.id());
                    return element.id().value();
                }),
                primitive("delete", "Deletes an element nothing references", List.of(Param.of("element", "element")),
                        (ctx, args) -> {
                            ctx.projection().delete(args.element("element", ctx.projection().graph()).id());
                            return null;
                        }),
                primitive("rename", "Renames an element and rewrites its uses", List.of(Param.of("element", "element"),
                        Param.of("name", "text")), (ctx, args) -> {
                    var element = args.element("element", ctx.projection().graph()).id();
                    ctx.projection().rename(element, args.text("name"));
                    return element.value();
                }),
                primitive("set", "Sets an attribute of an element", List.of(Param.of("element", "element"),
                        Param.of("attribute", "text"), Param.of("value", "text")), (ctx, args) -> {
                    var element = args.element("element", ctx.projection().graph()).id();
                    ctx.projection().set(element, args.text("attribute"), args.text("value"));
                    return element.value();
                }),
                primitive("extract", "Moves statements into a new method", List.of(Param.of("from", "element", "METHOD"),
                        Param.of("fragment", "text"), Param.of("into", "text")), (ctx, args) -> ctx.projection()
                        .extract(args.element("from", ctx.projection().graph()).id(), args.text("fragment"), args.text("into")).value()),
                primitive("inline", "Replaces a call by the called method's body", List.of(Param.of("call", "text")),
                        (ctx, args) -> {
                            ctx.projection().inline(args.text("call"));
                            return null;
                        }),
                primitive("retarget", "Points a reference at another element", List.of(Param.of("ref", "text"),
                        Param.of("target", "element")), (ctx, args) -> {
                    ctx.projection().retarget(args.text("ref"), args.element("target", ctx.projection().graph()).id());
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
