package ide.java.semantics;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnnotationDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserEnumDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserRecordDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves types declared in the in-memory workspace, falling back to its libraries: the JDK and the jars of its
 * classpath. The index of workspace types is dropped whenever units are replaced, so it never points into stale ASTs.
 */
public final class WorkspaceTypeSolver implements TypeSolver {

    private final Supplier<Collection<CompilationUnit>> units;
    private final TypeSolver libraries;
    private TypeSolver parent;
    private Map<String, ResolvedReferenceTypeDeclaration> index;

    public WorkspaceTypeSolver(Supplier<Collection<CompilationUnit>> units, TypeSolver libraries) {
        this.units = units;
        this.libraries = libraries;
        libraries.setParent(this);
    }

    /**
     * The JDK and the jars of a classpath, read without loading a class of them; a folder of classes is left to javac,
     * and a jar that cannot be read is left out, like one that is not there.
     */
    public static TypeSolver libraries(List<Path> classpath) {
        var solvers = new ArrayList<TypeSolver>();
        solvers.add(new ReflectionTypeSolver());
        for (var entry : classpath) {
            if (!entry.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(entry)) continue;
            try {
                solvers.add(new JarTypeSolver(entry));
            } catch (IOException | RuntimeException unreadable) {
                // not a library this projection can read
            }
        }
        return solvers.size() == 1 ? solvers.getFirst() : new CombinedTypeSolver(failure -> true, solvers);
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
    public void reset() {
        index = null;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        if (index == null) index = buildIndex();
        var type = index.get(name);
        if (type != null) return SymbolReference.solved(type);
        return libraries.tryToSolveType(name);
    }

    /** Qualified name → declaration for every type in the workspace: classes, interfaces, enums, records, annotations. */
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
            for (var type : cu.findAll(RecordDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> built.put(name, new JavaParserRecordDeclaration(type, getRoot())));
            }
            for (var type : cu.findAll(AnnotationDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(name -> built.put(name, new JavaParserAnnotationDeclaration(type, getRoot())));
            }
        }
        return built;
    }
}
