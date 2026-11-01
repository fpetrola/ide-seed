package ide.projection.kit;

import ide.program.Address;
import ide.program.Kind;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Location;
import ide.projection.Needs;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;

import java.util.List;
import java.util.Optional;

/**
 * A projection with no language: the program is kept as a graph ({@link MemoryGraph}) and each primitive changes the
 * graph directly ({@link MemoryPrimitives}), declaring the same change a language driver would. It is the reference
 * implementation of {@link ProjectionContract}, and the stand-in for a driver in the tests of the products above it.
 * <p>
 * What only a language knows is not modelled: there is no text (so no {@link #edit} and no diagnostics), uses bind by
 * id (so nothing is ever shadowed), a body is its text in no language ({@link ide.program.Body}) with the uses it
 * names, or uses given by hand with {@link ProgramBuilder#use}. An extract's fragment is a range of the method's uses
 * by position.
 */
public final class MemoryProjection implements Projection {

    /** No access is ever restricted, and every visibility exists. */
    public static final LanguageRules OPEN = new LanguageRules() {
        @Override
        public boolean accessible(Program program, ElementId site, ElementId target) {
            return true;
        }

        @Override
        public List<String> visibilities() {
            return List.of("private", "package", "protected", "public");
        }

        /** There is no language to write a value in. */
        @Override
        public String defaultValue(String type) {
            return "";
        }
    };

    /** Like {@link #OPEN}, except that a private member is accessible from its own top-level type only. */
    public static final LanguageRules PRIVATE_TO_TOP_LEVEL = new LanguageRules() {
        @Override
        public boolean accessible(Program program, ElementId site, ElementId target) {
            return !"private".equals(program.element(target).fact(Fact.VISIBILITY))
                    || program.topLevel(site).id().equals(program.topLevel(target).id());
        }

        @Override
        public List<String> visibilities() {
            return OPEN.visibilities();
        }

        @Override
        public String defaultValue(String type) {
            return OPEN.defaultValue(type);
        }
    };

    private final MemoryGraph graph = new MemoryGraph();
    private LanguageRules rules;
    private Program program;

    private MemoryProjection(LanguageRules rules) {
        this.rules = rules;
    }

    public static ProgramBuilder builder() {
        return new ProgramBuilder(new MemoryProjection(OPEN));
    }

    /** A projection holding {@code program}, read afresh: the same outline under new ids. */
    public static MemoryProjection of(Program program, LanguageRules rules) {
        var projection = new MemoryProjection(rules);
        projection.graph.load(program);
        return projection;
    }

    @Override
    public Program program() {
        if (program == null) program = graph.read();
        return program;
    }

    /** All or nothing: a primitive that is refused, or fails, leaves the graph as it was. */
    @Override
    public Change apply(Primitive primitive) {
        var before = program();
        var saved = graph.save();
        try {
            var change = MemoryPrimitives.apply(graph, primitive, before);
            program = null;
            return change;
        } catch (RuntimeException failed) {
            graph.restore(saved);
            program = null;
            throw failed;
        }
    }

    @Override
    public void edit(String path, String text) {
        throw new PrimitiveRejected("projection.no-sources", "a memory projection has no sources to edit");
    }

    @Override
    public Snapshot snapshot() {
        return graph.save();
    }

    @Override
    public void restore(Snapshot snapshot) {
        graph.restore((MemoryGraph.Saved) snapshot);
        program = null;
    }

    @Override
    public LanguageRules rules() {
        return rules;
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return List.of();
    }

    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        return Optional.empty();
    }

    /** There is no text: nothing is anywhere. */
    @Override
    public Optional<Location> locate(ElementId element) {
        return Optional.empty();
    }

    @Override
    public Optional<ElementId> inside(String path, int line, int column) {
        return Optional.empty();
    }

    @Override
    public Optional<Needs> needs(String use) {
        return graph.use(use).filter(u -> u.target() == null).map(MemoryGraph.Use::needs);
    }

    @Override
    public Optional<ElementId> elementAt(String path, int line, int column) {
        return Optional.empty();
    }

    void rules(LanguageRules rules) {
        this.rules = rules;
    }

    /** The file a top-level type is said to be in: a fact of the projection, which no primitive sets. */
    void file(ElementId type, String path) {
        graph.nodes.put(type, graph.nodes.get(type).with(Fact.FILE, path));
        program = null;
    }

    /** A use from {@code site} written {@code name} that binds nothing, looked up in {@code scope}, needing {@code needs}. */
    void unresolved(ElementId site, String name, Mode mode, ElementId scope, Needs needs) {
        graph.uses.add(new MemoryGraph.Use(site, null, name, mode, scope, needs));
        program = null;
    }

    /** A type of a library, with its package: the program names it, and no primitive may change it. */
    void library(String qualified, Kind kind) {
        var dot = qualified.lastIndexOf('.');
        var pkg = new Address.Package(qualified.substring(0, dot));
        var parent = graph.ids.has(pkg) ? graph.ids.get(pkg)
                : graph.library(pkg, Kind.PACKAGE, pkg.name(), null);
        graph.library(new Address.Type(qualified), kind, qualified.substring(dot + 1), parent);
        program = null;
    }

    void addUse(ElementId site, ElementId target, Mode mode) {
        graph.addUse(site, target, mode);
        program = null;
    }
}
