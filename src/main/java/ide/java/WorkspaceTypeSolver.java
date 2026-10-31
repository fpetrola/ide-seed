package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Resolves types declared in the in-memory workspace, falling back to the JDK.
 * Deliberately uncached: units are replaced after every edit, so cached declarations would point into stale ASTs.
 */
final class WorkspaceTypeSolver implements TypeSolver {

    private final Supplier<Collection<CompilationUnit>> units;
    private final TypeSolver jdk;
    private TypeSolver parent;

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

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        for (var cu : units.get()) {
            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.getFullyQualifiedName().orElse("").equals(name)) {
                    return SymbolReference.solved(type.isInterface()
                            ? new JavaParserInterfaceDeclaration(type, getRoot())
                            : new JavaParserClassDeclaration(type, getRoot()));
                }
            }
        }
        return jdk.tryToSolveType(name);
    }
}
