package ide.java.semantics;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.text.Sources;
import ide.program.ElementId;
import ide.projection.Location;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Where elements are in the text, both ways: the name a declaration gives, and what a name in the text means. A use
 * is found by the name it is written with, so a caret on {@code a} in {@code a.m(1)} is on {@code a}, not on the call.
 */
public final class Positions {

    private Positions() {
    }

    /** The whole stretch of an element's declaration (its comment left out); nowhere for what the sources do not declare. */
    public static Optional<Range> declared(Reader.Reading reading, ElementId element) {
        if (!reading.program().contains(element) || !reading.declarations().containsKey(element)) return Optional.empty();
        return Optional.of(reading.declaration(element).range());
    }

    /** Where the name of an element's declaration starts; nowhere for what the sources do not declare. */
    public static Optional<Location> of(Reader.Reading reading, ElementId element) {
        if (!reading.program().contains(element) || !reading.declarations().containsKey(element)) return Optional.empty();
        var declaration = reading.declaration(element);
        return name(declaration.node(), reading.program().element(element).name())
                .map(range -> new Location(declaration.unit().path(), range.begin.line, range.begin.column));
    }

    /** The element whose name is at a line and column of the file at {@code path}: declared there, or bound by a use. */
    public static Optional<ElementId> at(Reader.Reading reading, Sources sources, String path, int line, int column) {
        var position = new Position(line, column);
        var use = reading.uses().entrySet().stream()
                .filter(entry -> entry.getValue().unit().path().equals(path) && entry.getValue().contains(position))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().node()))
                .filter(entry -> name(entry.getValue(), null).filter(range -> range.contains(position)).isPresent())
                .min(Comparator.comparing((Map.Entry<String, Node> entry) -> size(name(entry.getValue(), null).orElseThrow())));
        if (use.isPresent()) { // a use that binds nothing means nothing yet
            var id = use.get().getKey();
            return reading.program().unresolved(id).isPresent() ? Optional.empty() : Optional.of(reading.program().reference(id).target());
        }
        return reading.declarations().entrySet().stream()
                .filter(entry -> entry.getValue().unit().path().equals(path) && entry.getValue().contains(position))
                .filter(entry -> name(entry.getValue().node(), reading.program().element(entry.getKey()).name())
                        .filter(range -> range.contains(position)).isPresent())
                .map(Map.Entry::getKey).findFirst();
    }

    /** The innermost element whose declaration a place of a file is inside: a statement, a local, a method, a type... */
    public static Optional<ElementId> inside(Reader.Reading reading, String path, int line, int column) {
        var position = new Position(line, column);
        return reading.declarations().entrySet().stream()
                .filter(entry -> entry.getValue().unit().path().equals(path))
                .filter(entry -> entry.getValue().contains(position))
                .min(Comparator.comparing((Map.Entry<ElementId, ide.java.syntax.Decl> entry) -> size(entry.getValue().range())))
                .map(Map.Entry::getKey);
    }

    /** Where the name of a declaration or a use is written; a field declaration names each of its variables. */
    private static Optional<Range> name(Node node, String variable) {
        return switch (node) {
            case FieldDeclaration field -> field.getVariables().stream().filter(v -> v.getNameAsString().equals(variable))
                    .findFirst().flatMap(v -> v.getName().getRange());
            case ObjectCreationExpr creation -> creation.getType().getName().getRange();
            case ClassOrInterfaceType type -> type.getName().getRange();
            case MethodReferenceExpr reference -> reference.getRange().map(range -> Range.range(range.end.line,
                    range.end.column - reference.getIdentifier().length() + 1, range.end.line, range.end.column));
            case NodeWithSimpleName<?> named -> named.getName().getRange();
            default -> Optional.empty();
        };
    }

    private static int size(Range range) {
        return (range.end.line - range.begin.line) * 1000 + range.end.column - range.begin.column;
    }
}
