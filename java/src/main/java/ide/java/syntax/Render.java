package ide.java.syntax;

import java.util.stream.Collectors;

/** Text for code that does not exist yet, written the way this driver writes code. */
public final class Render {

    private Render() {
    }

    /** A file declaring one public class or interface. */
    public static String typeFile(String pkg, String keyword, String name) {
        return "package " + pkg + ";\n\npublic " + keyword + " " + name + " {\n}\n";
    }

    /** A method that does nothing, its closing brace at {@code indent}. */
    public static String emptyMethod(String name, String indent) {
        return "void " + name + "() {\n" + indent + "}";
    }

    /** A block with one statement per line of {@code statements}, its braces at {@code indent}. */
    public static String block(String statements, String indent) {
        return "{\n" + statements.lines().map(line -> indent + "    " + line + "\n").collect(Collectors.joining()) + indent + "}";
    }
}
