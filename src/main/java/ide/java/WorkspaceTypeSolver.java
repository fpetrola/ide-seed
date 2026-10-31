package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves types declared in the in-memory workspace, falling back to the JDK. The index of workspace types is
 * dropped whenever units are replaced, so it never points into stale ASTs.
 */
final class WorkspaceTypeSolver implements TypeSolver {

    private final Supplier<Collection<CompilationUnit>> units;
    private final TypeSolver jdk;
    private TypeSolver parent;
    private Map<String, ResolvedReferenceTypeDeclaration> index;

    WorkspaceTypeSolver(Supplier<Collection<CompilationUnit>> units, TypeSolver jdk) {
        this.units = units;
        this.jdk = jdk;
        jdk.setParent(this);
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        this.parent = parent;
    }

    /** Forgets the index; called whenever units are replaced. */
    void reset() {
        index = null;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        if (index == null) index = buildIndex();
        var type = index.get(name);
        if (type != null) return SymbolReference.solved(type);
        return jdk.tryToSolveType(name);
    }

    /** Qualified name → declaration for every class, interface and enum in the workspace. */
    private Map<String, ResolvedReferenceTypeDeclaration> buildIndex() {
        var built = new HashMap<String, ResolvedReferenceTypeDeclaration>();
        for (var cu : units.get()) {
            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> built.put(name, type.isInterface()
                        ? new JavaParserInterfaceDeclaration(type, getRoot())
                        : new JavaParserClassDeclaration(type, getRoot())));
            }
            for (var type : cu.findAll(EnumDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> built.put(name, new JavaParserEnumDeclaration(type, getRoot())));
            }
        }
        return built;
    }
}
