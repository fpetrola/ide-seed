package ide.java.syntax;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ide.java.text.Unit;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a file declares that other files can see: its types, each with its header as written (annotations, modifiers,
 * name, type parameters, supertypes), and their members as written up to their bodies (modifiers, types, names,
 * parameters, what they throw), and its imports, which say what those name. Two versions of a file with the same
 * shape differ only inside bodies and initializers, where nothing outside the file looks. It is read off the text,
 * which is already there.
 */
public record Shape(Map<String, String> types, Map<String, String> members, java.util.List<String> imports) {

    /** The shape of no file: what a file added or removed is compared with. */
    public static final Shape NONE = new Shape(Map.of(), Map.of(), java.util.List.of());

    /** A member whose declaration changed: its type, by qualified name, and its simple name. */
    public record Member(String owner, String name) {
    }

    public static Shape of(Unit unit) {
        var types = new LinkedHashMap<String, String>();
        var members = new LinkedHashMap<String, String>();
        for (var type : unit.tree().findAll(TypeDeclaration.class)) {
            var owner = type.getFullyQualifiedName().orElse(type.getNameAsString()) + "#";
            types.put(owner, upTo(unit, type, unit.next('{', type.getName().getEnd().orElseThrow())));
            for (var member : type.getMembers()) {
                switch (member) {
                    case FieldDeclaration field -> {
                        var declared = unit.textBetween(field.getBegin().orElseThrow(), field.getElementType().getEnd().orElseThrow());
                        field.getVariables().forEach(variable -> members.put(owner + variable.getNameAsString(), declared + " "
                                + variable.getNameAsString() + "[]".repeat(variable.getType().getArrayLevel() - field.getElementType().getArrayLevel())));
                    }
                    case MethodDeclaration method -> members.put(owner + signature(unit, method), method.getBody()
                            .map(body -> upTo(unit, method, body.getBegin().orElseThrow())).orElseGet(() -> unit.textOf(method)));
                    case ConstructorDeclaration constructor -> members.put(owner + signature(unit, constructor),
                            upTo(unit, constructor, constructor.getBody().getBegin().orElseThrow()));
                    case AnnotationMemberDeclaration element -> members.put(owner + element.getNameAsString() + "()", unit.textOf(element));
                    default -> {
                        // nested types are types of the file; initializers are bodies
                    }
                }
            }
            if (type instanceof EnumDeclaration enumeration) {
                enumeration.getEntries().forEach(constant -> members.put(owner + constant.getNameAsString(), "constant"));
            }
        }
        var imports = unit.tree().getImports().stream().map(i -> unit.textOf(i)).sorted().toList();
        return new Shape(Map.copyOf(types), Map.copyOf(members), imports);
    }

    /**
     * The members whose declarations differ in {@code after}, those added and removed included, and every member
     * when the imports differ (what they name may be another type); null when a type itself differs, since then any
     * name anywhere may resolve differently.
     */
    public Set<Member> changedMembers(Shape after) {
        if (!types.equals(after.types)) return null;
        var changed = new HashSet<Member>();
        var renamed = !imports.equals(after.imports);
        for (var key : union(members.keySet(), after.members.keySet())) {
            if (renamed || !Objects.equals(members.get(key), after.members.get(key))) {
                changed.add(new Member(key.substring(0, key.indexOf('#')), simpleName(key)));
            }
        }
        return changed;
    }

    /** A callable's name and its parameter types as written: {@code name(int,List<String>)}. */
    private static String signature(Unit unit, CallableDeclaration<?> callable) {
        var signature = new StringBuilder(callable.getNameAsString()).append('(');
        for (var i = 0; i < callable.getParameters().size(); i++) {
            if (i > 0) signature.append(',');
            signature.append(unit.textOf(callable.getParameter(i).getType()));
        }
        return signature.append(')').toString();
    }

    /** The text of a declaration from its start to just before {@code end} (its body's brace). */
    private static String upTo(Unit unit, Node declaration, Position end) {
        var text = unit.textBetween(declaration.getBegin().orElseThrow(), end);
        return text.substring(0, text.length() - 1);
    }

    /** {@code a.B#name(int)} is {@code name}; a constructor's name is its type's, as {@code new B(...)} writes it. */
    private static String simpleName(String key) {
        var member = key.substring(key.indexOf('#') + 1);
        var paren = member.indexOf('(');
        return paren < 0 ? member : member.substring(0, paren);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
