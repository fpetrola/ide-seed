package ide.java;

import java.util.List;

/** The JDK types offered for import: a short list of common packages, looked up by simple name. */
final class JdkTypes {

    private static final List<String> PACKAGES = List.of("java.util", "java.util.function", "java.util.stream",
            "java.util.concurrent", "java.util.regex", "java.io", "java.nio.file", "java.time", "java.math", "java.net");

    private JdkTypes() {
    }

    static List<String> named(String simpleName) {
        return PACKAGES.stream().map(p -> p + "." + simpleName).filter(JdkTypes::exists).toList();
    }

    private static boolean exists(String qualified) {
        try {
            Class.forName(qualified, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
