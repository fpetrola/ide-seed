package ide.projection.kit;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.projection.Needs;
import ide.program.Primitive;
import ide.projection.LanguageRules;

/**
 * Builds a program by hand, by address: {@code type("a.A").field("a.A", "x", "int").method("a.A", "m", "int",
 * "int p").use("a.A#m(int)", "a.A#x", Mode.READ)}. A type written by qualified name that is in the workspace is
 * linked, any other is text. Everything but the uses is made with the projection's own primitives.
 */
public final class ProgramBuilder {

    private final MemoryProjection projection;

    ProgramBuilder(MemoryProjection projection) {
        this.projection = projection;
    }

    /** The rules the projection answers with (default {@link MemoryProjection#OPEN}). */
    public ProgramBuilder rules(LanguageRules rules) {
        projection.rules(rules);
        return this;
    }

    public ProgramBuilder type(String qualified) {
        return type(Kind.CLASS, qualified);
    }

    /** A class, interface or enum; its package (or outer type) is created on the way if needed. */
    public ProgramBuilder type(Kind kind, String qualified) {
        var dot = qualified.lastIndexOf('.');
        var outer = qualified.substring(0, dot);
        var parent = projection.program().find(outer).map(Element::id)
                .orElseGet(() -> projection.apply(new Primitive.Create(Kind.PACKAGE, null, outer)).produced());
        projection.apply(new Primitive.Create(kind, parent, qualified.substring(dot + 1)));
        return this;
    }

    /** A type of a library ({@code java.lang.String}): the program can name it, and nothing changes it. */
    public ProgramBuilder library(String qualified, Kind kind) {
        projection.library(qualified, kind);
        return this;
    }

    /** The source file a top-level type is in ({@code a/U.java}), as a projection with files would say. */
    public ProgramBuilder file(String type, String path) {
        projection.file(id(type), path);
        return this;
    }

    /** A use from {@code site}, written {@code name}, that binds nothing. */
    public ProgramBuilder unresolved(String site, String name, Mode mode) {
        projection.unresolved(id(site), name, mode, null, null);
        return this;
    }

    /** A use of a member from {@code site} that binds nothing: looked up in {@code scope}, needing {@code needs}. */
    public ProgramBuilder unresolved(String site, String name, Mode mode, String scope, Needs needs) {
        projection.unresolved(id(site), name, mode, id(scope), needs);
        return this;
    }

    public ProgramBuilder field(String owner, String name, String type) {
        declare(projection.apply(new Primitive.Create(Kind.FIELD, id(owner), name)).produced(), type);
        return this;
    }

    /** A method returning {@code type}, with parameters written {@code "type name"}. */
    public ProgramBuilder method(String owner, String name, String type, String... params) {
        var method = projection.apply(new Primitive.Create(Kind.METHOD, id(owner), name)).produced();
        declare(method, type);
        for (var param : params) {
            var space = param.lastIndexOf(' ');
            var created = projection.apply(new Primitive.Create(Kind.PARAM, method, param.substring(space + 1))).produced();
            declare(created, param.substring(0, space));
        }
        return this;
    }

    /** One more statement at the end of a method's body. */
    public ProgramBuilder statement(String method) {
        projection.apply(new Primitive.Create(Kind.STATEMENT, id(method), ""));
        return this;
    }

    /** A local of a method, of {@code type}: its uses are made from the method. */
    public ProgramBuilder local(String method, String name, String type) {
        var local = projection.apply(new Primitive.Create(Kind.LOCAL, id(method), name)).produced();
        declare(local, type);
        return this;
    }

    public ProgramBuilder extend(String type, String superclass) {
        projection.apply(new Primitive.Link(id(type), Mode.EXTENDS, id(superclass)));
        return this;
    }

    public ProgramBuilder implement(String type, String implemented) {
        projection.apply(new Primitive.Link(id(type), Mode.IMPLEMENTS, id(implemented)));
        return this;
    }

    public ProgramBuilder fact(String element, Fact fact, String value) {
        projection.apply(new Primitive.Set(id(element), fact.label(), value));
        return this;
    }

    /**
     * A use made from inside {@code site}: what a body would say; a use of a member is made on the object itself. A
     * statement as the site ({@code a.A#m()[2]}) makes the use from its method, in that statement.
     */
    public ProgramBuilder use(String site, String target, Mode mode) {
        projection.addUse(id(site), id(target), mode);
        return this;
    }

    /** A use made from inside {@code site} on {@code receiver}: through a type, or through another object. */
    public ProgramBuilder use(String site, String target, Mode mode, ide.program.Reference.Receiver receiver) {
        projection.addUse(id(site), id(target), mode, receiver);
        return this;
    }

    public MemoryProjection build() {
        return projection;
    }

    private void declare(ElementId element, String type) {
        var workspace = projection.program().find(type).filter(e -> e.kind().isType());
        if (workspace.isPresent()) projection.apply(new Primitive.Link(element, Mode.DECLARED, workspace.get().id()));
        else projection.apply(new Primitive.Set(element, "type", type));
    }

    private ElementId id(String address) {
        return projection.program().find(address).map(Element::id)
                .orElseThrow(() -> new IllegalArgumentException("no element " + address));
    }
}
