package ide.program;

import ide.core.TextDiff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * After an edit made by hand the program is read again: what kept its address kept its id, and what changed address
 * got a new one. Reconciliation pairs each new element with the element of before it is, renamed, re-signed or moved
 * to another package, but only when the structure leaves no doubt: an element is paired with the one candidate that
 * has it as its one candidate. The evidence is what an edit by hand tends to keep: a field's type, a method's name or
 * body, a parameter's place, what a type holds; or being the one element of its kind that left a parent while one
 * came. A round pairs elements whose parents both programs share; once the parents are paired and the program read
 * again, their children can be.
 */
public final class Reconciliation {

    private Reconciliation() {
    }

    /** New element → the element of before it is. */
    public static Map<ElementId, ElementId> pairs(Program before, Program after) {
        var gone = before.elements().stream().filter(e -> !after.contains(e.id()) && !e.inLibrary()).toList();
        var born = after.elements().stream().filter(e -> !before.contains(e.id()) && !e.inLibrary()).toList();
        var leaving = count(gone);
        var coming = count(born);
        var pairs = new LinkedHashMap<ElementId, ElementId>();
        for (var fresh : born) {
            var olds = gone.stream().filter(old -> same(old, before, fresh, after, leaving, coming)).toList();
            if (olds.size() != 1) continue;
            var old = olds.getFirst();
            if (born.stream().filter(other -> same(old, before, other, after, leaving, coming)).count() == 1) {
                pairs.put(fresh.id(), old.id());
            }
        }
        return pairs;
    }

    /**
     * The statements of a body after an edit by hand, paired with those of before by their order and text (what both
     * say in order, the most they can): for each statement after, the one of before it is, or null if it is new. A
     * statement whose text changed is new, which is safe.
     */
    public static List<ElementId> statements(List<ElementId> before, List<String> said, List<String> saying) {
        var paired = new ArrayList<ElementId>();
        for (var place : TextDiff.common(said, saying)) paired.add(place < 0 ? null : before.get(place));
        return paired;
    }

    private static boolean same(Element old, Program before, Element fresh, Program after,
                                Map<List<Object>, Integer> leaving, Map<List<Object>, Integer> coming) {
        if (old.kind() != fresh.kind()) return false;
        var sameParent = Objects.equals(old.parent(), fresh.parent());
        var sole = sameParent && leaving.get(group(old)) == 1 && coming.get(group(fresh)) == 1;
        return switch (old.kind()) {
            case PACKAGE -> false; // a package comes and goes with its types, which are paired themselves
            case CLASS, INTERFACE, ENUM -> sameParent ? shape(before, old).equals(shape(after, fresh))
                    : old.name().equals(fresh.name()) && inPackage(before, old) && inPackage(after, fresh);
            case FIELD -> sole || sameParent && Objects.equals(old.fact(Fact.TYPE), fresh.fact(Fact.TYPE));
            case METHOD -> sole || sameParent && (old.name().equals(fresh.name()) || sameBody(old, fresh));
            case CONSTRUCTOR -> sameParent;
            case PARAM -> sole || sameParent && position(before, old) == position(after, fresh)
                    && (old.name().equals(fresh.name()) || Objects.equals(old.fact(Fact.TYPE), fresh.fact(Fact.TYPE)));
            case LOCAL -> sole; // the one local of its method that went, and the one that came
            case STATEMENT -> false; // a statement has no name: the projection pairs them by their order and text
        };
    }

    /** How many elements of each kind left, or came to, each parent. */
    private static Map<List<Object>, Integer> count(List<Element> elements) {
        var counts = new HashMap<List<Object>, Integer>();
        for (var element : elements) counts.merge(group(element), 1, Integer::sum);
        return counts;
    }

    private static List<Object> group(Element element) {
        return Arrays.asList(element.parent(), element.kind()); // a package has no parent
    }

    private static boolean sameBody(Element old, Element fresh) {
        return old.fact(Fact.BODY) != null && old.fact(Fact.BODY).equals(fresh.fact(Fact.BODY));
    }

    /** What a type holds, by kind and name; a constructor by {@code new}, since its name is the type's. */
    private static List<String> shape(Program program, Element type) {
        return program.children(type.id()).stream()
                .map(child -> child.kind() + " " + (child.kind() == Kind.CONSTRUCTOR ? "new" : child.name())).sorted().toList();
    }

    private static boolean inPackage(Program program, Element type) {
        return type.parent() != null && program.element(type.parent()).kind() == Kind.PACKAGE;
    }

    private static int position(Program program, Element param) {
        return program.children(param.parent()).indexOf(param);
    }
}
