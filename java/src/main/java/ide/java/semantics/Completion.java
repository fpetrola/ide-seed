package ide.java.semantics;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import ide.java.text.Sources;
import ide.program.Address;
import ide.program.Identity;
import ide.projection.CompletionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** What is being completed in an editor's text, which may not parse as it is. */
public final class Completion {

    private static final String MARKER = "__ide_complete__";

    private Completion() {
    }

    /**
     * Inserts a marker identifier at the cursor (and a {@code ;} if needed to parse), parses that copy and resolves
     * the receiver of the marker: the scope's type for {@code expr.prefix}, the enclosing type for a bare name.
     * The identities are those of the saved version, which the edited text is matched against.
     */
    public static Optional<CompletionContext> at(Sources sources, Reader reader, Identity ids, String text, int offset) {
        var start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        var prefix = text.substring(start, offset);
        var parsed = sources.parseDetached(text.substring(0, offset) + MARKER + text.substring(offset))
                .or(() -> sources.parseDetached(text.substring(0, offset) + MARKER + ";" + text.substring(offset)));
        if (parsed.isEmpty()) return Optional.empty();
        var name = prefix + MARKER;
        var site = parsed.get().findFirst(Node.class, n -> n instanceof NodeWithSimpleName<?> named
                && named.getNameAsString().equals(name) && (n instanceof NameExpr || n instanceof FieldAccessExpr));
        if (site.isEmpty()) return Optional.empty();
        var siteId = reader.siteOf(site.get());
        if (siteId == null) return Optional.empty();
        try {
            if (site.get() instanceof FieldAccessExpr access) {
                var typeName = access.getScope() instanceof NameExpr scopeName ? reader.resolveTypeName(scopeName) : null;
                var receiver = typeName != null ? typeName
                        : access.getScope().calculateResolvedType().asReferenceType().getQualifiedName();
                return Optional.ofNullable(ids.get(new Address.Type(receiver)))
                        .map(r -> new CompletionContext(r, prefix, siteId, typeName != null, List.of()));
            }
            var enclosing = Reader.qualifiedOf(site.get().findAncestor(TypeDeclaration.class).orElseThrow());
            return Optional.ofNullable(ids.get(new Address.Type(enclosing)))
                    .map(r -> new CompletionContext(r, prefix, siteId, false, localsBefore(site.get())));
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /** Locals declared before {@code node} in its method, then the method's parameters; nearest first. */
    private static List<String> localsBefore(Node node) {
        var method = node.findAncestor(CallableDeclaration.class);
        if (method.isEmpty()) return List.of();
        var position = node.getBegin().orElseThrow();
        var locals = new ArrayList<String>();
        method.get().findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getEnd().orElseThrow().isBefore(position))
                .forEach(v -> locals.addFirst(v.getNameAsString()));
        ((CallableDeclaration<?>) method.get()).getParameters().reversed().forEach(p -> locals.add(p.getNameAsString()));
        return locals;
    }
}
