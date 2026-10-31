package ide.java.primitives;

import com.github.javaparser.ast.Node;
import ide.java.semantics.Keys;
import ide.java.semantics.Reader;
import ide.java.syntax.Decl;
import ide.java.text.Sources;
import ide.program.Element;
import ide.program.Identity;
import ide.program.Program;

/**
 * What a primitive in Java works with: the program as last read, the sources it edits, the identity it carries.
 * The reading goes stale with every edit; a primitive reads what it needs first, edits, then moves identity along.
 */
public interface Context {

    Program program();

    /** Where an element is declared, as last read. */
    Decl declaration(Element element);

    /** The node of a reference, as last read. */
    Node use(String reference);

    /** The text: the only way to change anything. */
    Sources sources();

    /** Who is who: a primitive moves the ids of the addresses it changes. */
    Identity ids();

    Keys keys();

    /** What names in the code resolve to. */
    Reader reader();

    /** A package that exists before it has a file. */
    void declarePackage(String name);
}
