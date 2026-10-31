package ide.graph;

/** A program that can be changed only through primitives; every change is visible in the next {@link #graph()}. */
public interface Program {

    ProgramGraph graph();

    /** Creates an empty element of {@code kind} named {@code name} inside {@code parent}. */
    ElementId create(Kind kind, ElementId parent, String name);

    default void move(ElementId member, ElementId newParent) {
        move(member, newParent, null);
    }

    /** Moves a member into {@code newParent}, just before {@code before} (a member there), or at the end if null. */
    void move(ElementId member, ElementId newParent, ElementId before);

    void set(ElementId element, String attribute, String value);
}
