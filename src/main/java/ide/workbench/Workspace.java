package ide.workbench;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.util.Map;
import java.util.TreeMap;

/** Java sources held in memory, edited with lexical preservation. */
public final class Workspace {

    private final Map<String, CompilationUnit> units = new TreeMap<>();

    public static Workspace of(Map<String, String> sources) {
        var ws = new Workspace();
        sources.forEach((path, code) ->
                ws.units.put(path, LexicalPreservingPrinter.setup(StaticJavaParser.parse(code))));
        return ws;
    }

    public String source(String path) {
        return LexicalPreservingPrinter.print(units.get(path));
    }

    public void invoke(String capability, Map<String, String> args) {
        if (!capability.equals("pull-up-field")) throw new IllegalArgumentException("unknown capability: " + capability);
        pullUpField(args.get("field"));
    }

    private void pullUpField(String address) {
        var hash = address.indexOf('#');
        var owner = type(address.substring(0, hash));
        var name = address.substring(hash + 1);
        var field = owner.getFieldByName(name).orElseThrow();
        var superName = owner.getExtendedTypes(0).getNameAsString();
        var pkg = owner.findCompilationUnit().orElseThrow().getPackageDeclaration()
                .map(p -> p.getNameAsString() + ".").orElse("");
        var target = type(pkg + superName);

        var text = LexicalPreservingPrinter.print(field).strip();
        field.remove();
        insertMember(target, text);
    }

    /** Inserts a member as a text edit just before the type's closing brace, then re-parses the unit. */
    private void insertMember(ClassOrInterfaceDeclaration type, String memberText) {
        var cu = type.findCompilationUnit().orElseThrow();
        var path = pathOf(cu);
        var code = source(path);
        var closingLine = type.getEnd().orElseThrow().line;
        var offset = lineStart(code, closingLine);
        var indent = " ".repeat(4);
        var edited = code.substring(0, offset) + "\n" + indent + memberText + "\n" + code.substring(offset);
        units.put(path, LexicalPreservingPrinter.setup(StaticJavaParser.parse(edited)));
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

    private ClassOrInterfaceDeclaration type(String qualified) {
        return units.values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(t -> t.getFullyQualifiedName().orElse("").equals(qualified))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no type " + qualified));
    }
}
