package ide.workbench;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ide.coherence.Result;
import ide.coherence.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Java sources held in memory, edited with lexical preservation. */
public final class Workspace {

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver())));
    private final Map<String, CompilationUnit> units = new TreeMap<>();

    public static Workspace of(Map<String, String> sources) {
        var ws = new Workspace();
        sources.forEach(ws::load);
        return ws;
    }

    public String source(String path) {
        return LexicalPreservingPrinter.print(units.get(path));
    }

    public Result invoke(String capability, Map<String, String> args) {
        if (!capability.equals("pull-up-field")) throw new IllegalArgumentException("unknown capability: " + capability);
        var snapshot = snapshot();
        var violations = pullUpField(args.get("field"));
        if (violations.isEmpty()) return Result.ok();
        snapshot.forEach(this::load);
        return Result.aborted(violations);
    }

    private List<Violation> pullUpField(String address) {
        var hash = address.indexOf('#');
        var owner = type(address.substring(0, hash));
        var name = address.substring(hash + 1);
        var field = owner.getFieldByName(name).orElseThrow();
        var superName = owner.getExtendedTypes(0).getNameAsString();
        var pkg = owner.findCompilationUnit().orElseThrow().getPackageDeclaration()
                .map(p -> p.getNameAsString() + ".").orElse("");
        var target = type(pkg + superName);
        var accessors = referencingTypes(owner, name);

        var isPrivate = field.hasModifier(Modifier.Keyword.PRIVATE);
        var text = LexicalPreservingPrinter.print(field).strip();
        field.remove();
        insertMember(target, text);

        var targetName = pkg + superName;
        var violations = new ArrayList<Violation>();
        if (isPrivate) {
            for (var site : accessors) {
                if (!site.equals(targetName)) violations.add(new Violation("access.insufficient",
                        site + " cannot access private " + targetName + "#" + name));
            }
        }
        return violations;
    }

    /** Qualified names of the top-level types holding a reference that resolves to field {@code name} of {@code owner}. */
    private List<String> referencingTypes(ClassOrInterfaceDeclaration owner, String name) {
        var ownerName = owner.getFullyQualifiedName().orElseThrow();
        var result = new ArrayList<String>();
        for (var cu : units.values()) {
            var refs = new ArrayList<Expression>();
            refs.addAll(cu.findAll(NameExpr.class, n -> n.getNameAsString().equals(name)));
            refs.addAll(cu.findAll(FieldAccessExpr.class, n -> n.getNameAsString().equals(name)));
            for (var ref : refs) {
                if (resolvesTo(ref, ownerName, name)) {
                    var site = ref.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow()
                            .getFullyQualifiedName().orElseThrow();
                    if (!result.contains(site)) result.add(site);
                }
            }
        }
        return result;
    }

    private static boolean resolvesTo(Expression ref, String ownerName, String name) {
        ResolvedValueDeclaration decl = ref.isNameExpr() ? ref.asNameExpr().resolve() : ref.asFieldAccessExpr().resolve();
        return decl.isField() && decl.getName().equals(name)
                && ((ResolvedFieldDeclaration) decl).declaringType().getQualifiedName().equals(ownerName);
    }

    /** Inserts a member as a text edit just before the type's closing brace, then re-parses the unit. */
    private void insertMember(ClassOrInterfaceDeclaration type, String memberText) {
        var path = pathOf(type.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = lineStart(code, type.getEnd().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + "\n" + indent + memberText + "\n" + code.substring(offset));
    }

    private void load(String path, String code) {
        units.put(path, LexicalPreservingPrinter.setup(parser.parse(code).getResult().orElseThrow()));
    }

    private Map<String, String> snapshot() {
        var copy = new LinkedHashMap<String, String>();
        units.keySet().forEach(path -> copy.put(path, source(path)));
        return copy;
    }

    private ClassOrInterfaceDeclaration type(String qualified) {
        return units.values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(t -> t.getFullyQualifiedName().orElse("").equals(qualified))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no type " + qualified));
    }

    private String pathOf(CompilationUnit cu) {
        return units.entrySet().stream().filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static int lineStart(String code, int line) {
        var offset = 0;
        for (var i = 1; i < line; i++) offset = code.indexOf('\n', offset) + 1;
        return offset;
    }
}
