package ide.java.syntax;

import com.github.javaparser.ast.ImportDeclaration;
import ide.java.text.EditSet;
import ide.java.text.Unit;

/** The imports of a unit, edited as lines. */
public final class Imports {

    private Imports() {
    }

    /** An import after the last one, or in a block of its own after the package line. */
    public static void add(EditSet edits, Unit unit, String qualified) {
        var imports = unit.tree().getImports();
        if (imports.isNonEmpty()) {
            edits.insertAtLine(unit, imports.getLast().orElseThrow().getEnd().orElseThrow().line + 1, "import " + qualified + ";\n");
        } else {
            var line = unit.tree().getPackageDeclaration().map(p -> p.getEnd().orElseThrow().line).orElse(0);
            edits.insertAtLine(unit, line + 1, "\nimport " + qualified + ";\n");
        }
    }

    public static void replace(EditSet edits, Unit unit, ImportDeclaration declaration, String qualified) {
        edits.replace(unit, declaration, "import " + qualified + ";");
    }

    /** An import's lines; a blank line it leaves doubled goes with it. */
    public static void remove(EditSet edits, Unit unit, ImportDeclaration declaration) {
        edits.removeLines(unit, declaration.getBegin().orElseThrow().line, declaration.getEnd().orElseThrow().line);
    }

    /** The single-type import of {@code qualified}, if the unit has one. */
    public static java.util.Optional<ImportDeclaration> of(Unit unit, String qualified) {
        return unit.tree().getImports().stream()
                .filter(i -> !i.isAsterisk() && !i.isStatic() && i.getNameAsString().equals(qualified)).findFirst();
    }

    /** Makes {@code qualified} nameable by its simple name in {@code unit}, importing it if needed; answers that name. */
    public static String nameable(EditSet edits, Unit unit, String qualified) {
        if (!names(unit, qualified)) add(edits, unit, qualified);
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** The package a unit declares (empty for the default package). */
    public static String packageOf(Unit unit) {
        return unit.tree().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    /** Whether the unit names {@code qualified} by its simple name already: same package, or imported. */
    public static boolean names(Unit unit, String qualified) {
        var dot = qualified.lastIndexOf('.');
        var pkg = dot < 0 ? "" : qualified.substring(0, dot);
        return pkg.equals(packageOf(unit)) || unit.tree().getImports().stream().anyMatch(i -> i.getNameAsString().equals(qualified));
    }
}
