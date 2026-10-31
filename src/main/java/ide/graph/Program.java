package ide.graph;

/** A program that can be changed only through primitives; every change is visible in the next {@link #graph()}. */
public interface Program {

    ProgramGraph graph();

    void move(ElementId member, ElementId newParent);

    void set(ElementId element, String attribute, String value);
}
