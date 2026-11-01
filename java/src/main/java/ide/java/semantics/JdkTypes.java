package ide.java.semantics;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** The JDK types a written type that binds nothing may mean: a short list of common packages, by simple name. */
final class JdkTypes {

    private static final List<String> PACKAGES = List.of("java.util", "java.util.function", "java.util.stream",
            "java.util.concurrent", "java.util.regex", "java.io", "java.nio.file", "java.time", "java.math", "java.net");

    private JdkTypes() {
    }

    /** The public types of the usual packages named {@code simpleName}: what a type use that binds nothing may mean. */
    static List<Class<?>> named(String simpleName) {
        var found = new ArrayList<Class<?>>();
        for (var pkg : PACKAGES) {
            try {
                var type = Class.forName(pkg + "." + simpleName, false, ClassLoader.getPlatformClassLoader());
                if (Modifier.isPublic(type.getModifiers())) found.add(type);
            } catch (ClassNotFoundException | LinkageError absent) {
                // not in this package
            }
        }
        return found;
    }
}
