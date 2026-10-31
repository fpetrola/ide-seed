package ide.java.syntax;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import ide.java.text.EditSet;
import ide.java.text.Unit;

import java.util.Optional;
import java.util.Set;

/** A declaration's modifiers, edited as keywords: the access keyword first, the others right after it. */
public final class Modifiers {

    private static final Set<Modifier.Keyword> ACCESS =
            Set.of(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE);

    private Modifiers() {
    }

    /** Gives the declaration the access {@code visibility}: a keyword, or none for package access. */
    public static void visibility(EditSet edits, Unit unit, NodeWithModifiers<?> declaration, String visibility) {
        var current = access(declaration);
        if (visibility.equals("package")) current.ifPresent(keyword -> edits.removeWithSpaceAfter(unit, keyword));
        else if (current.isPresent()) edits.replace(unit, current.get(), visibility);
        else edits.insertAt(unit, start(unit, declaration), visibility + " ");
    }

    /** Adds or removes a keyword such as {@code static}, {@code final} or {@code abstract}. */
    public static void flag(EditSet edits, Unit unit, NodeWithModifiers<?> declaration, Modifier.Keyword keyword, boolean on) {
        var present = declaration.getModifiers().stream().filter(m -> m.getKeyword() == keyword).findFirst();
        if (!on) {
            present.ifPresent(m -> edits.removeWithSpaceAfter(unit, m));
        } else if (present.isEmpty()) {
            var access = access(declaration);
            if (access.isPresent()) edits.insertAfter(unit, access.get(), " " + keyword.asString());
            else edits.insertAt(unit, start(unit, declaration), keyword.asString() + " ");
        }
    }

    private static Optional<Modifier> access(NodeWithModifiers<?> declaration) {
        return declaration.getModifiers().stream().filter(m -> ACCESS.contains(m.getKeyword())).findFirst();
    }

    /** Where a declaration's modifiers start: its first modifier, else the first token after its annotations. */
    private static Position start(Unit unit, NodeWithModifiers<?> declaration) {
        var modifiers = declaration.getModifiers();
        if (modifiers.isNonEmpty()) return modifiers.getFirst().orElseThrow().getBegin().orElseThrow();
        if (declaration instanceof NodeWithAnnotations<?> annotated && annotated.getAnnotations().isNonEmpty()) {
            return unit.nextToken(annotated.getAnnotations().getLast().orElseThrow().getEnd().orElseThrow());
        }
        return ((Node) declaration).getBegin().orElseThrow();
    }
}
