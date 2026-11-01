package ide.java.syntax;

import java.util.List;
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

    /** What a field of a type starts with: a primitive's zero, {@code false} or {@code '\0'}; {@code null} for any other. */
    public static String defaultValue(String type) {
        return switch (type) {
            case "byte", "short", "int", "long", "float", "double" -> "0";
            case "boolean" -> "false";
            case "char" -> "'\\0'";
            default -> "null";
        };
    }

    public static String returning(String value) {
        return "return " + value + ";";
    }

    public static String assignment(String target, String value) {
        return target + " = " + value + ";";
    }

    /** {@code new Type(a, b)}. */
    public static String creation(String type, List<String> arguments) {
        return "new " + type + "(" + String.join(", ", arguments) + ")";
    }

    /** A member through what it is reached from: {@code this.x}, {@code P.count}. */
    public static String through(String receiver, String member) {
        return receiver + "." + member;
    }
}
