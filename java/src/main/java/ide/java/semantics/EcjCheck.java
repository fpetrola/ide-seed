package ide.java.semantics;

import ide.projection.Diagnostic;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Eclipse's compiler over in-memory sources, incremental as Eclipse's builder is: a check compiles again only the
 * files whose text changed, and then the files that name a type whose shape changed (its class file has structural
 * changes, or it came or went), until nothing else changes. What is not compiled again is read from the class files
 * of the last check. Errors and warnings are reported as {@code ecj.<problem>}, with their line, column and length.
 */
public final class EcjCheck {

    /** The names of Eclipse's problems, by id. */
    private static final Map<Integer, String> NAMES = names();

    /** What one file gave the last time it was compiled. */
    private record Compiled(String text, List<Diagnostic> problems, Map<String, byte[]> classes) {
    }

    private final List<Path> classpath;
    private final Map<String, Compiled> compiled = new HashMap<>();
    private Set<String> compiledLastTime = Set.of();
    private FileSystem libraries;

    public EcjCheck(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
    }

    /** The files the last check compiled. */
    public Set<String> compiledLastTime() {
        return compiledLastTime;
    }

    /** The problems of the sources, by path: compiled again where they changed, and where that matters. */
    public List<Diagnostic> check(Map<String, String> sources) {
        var removed = new HashSet<String>();
        typesByFile().forEach((path, types) -> {
            if (!sources.containsKey(path)) removed.addAll(types);
        });
        compiled.keySet().retainAll(sources.keySet());
        var pending = new TreeSet<String>();
        sources.forEach((path, text) -> {
            if (!compiled.containsKey(path) || !compiled.get(path).text().equals(text)) pending.add(path);
        });
        sources.forEach((path, text) -> {
            if (names(text, removed)) pending.add(path);
        });
        var done = new TreeSet<String>();
        var changedTypes = new HashSet<String>();
        while (!pending.isEmpty()) {
            var round = new TreeSet<>(pending);
            pending.clear();
            var old = new HashMap<String, Map<String, byte[]>>();
            for (var path : round) old.put(path, compiled.containsKey(path) ? compiled.get(path).classes() : Map.of());
            compile(round, sources);
            done.addAll(round);
            for (var path : round) changedTypes.addAll(changed(old.get(path), compiled.get(path).classes()));
            for (var path : sources.keySet()) {
                if (!done.contains(path) && names(sources.get(path), changedTypes)) pending.add(path);
            }
            changedTypes.clear();
        }
        compiledLastTime = Set.copyOf(done);
        return sources.keySet().stream().sorted().flatMap(path -> compiled.get(path).problems().stream()).toList();
    }

    /** Compiles some files, reading the others from their class files, or from their text if they have none yet. */
    private void compile(Set<String> paths, Map<String, String> sources) {
        var units = paths.stream().map(path -> new CompilationUnit(sources.get(path).toCharArray(), path, "UTF-8")).toArray(CompilationUnit[]::new);
        var environment = new Environment(paths, sources);
        var options = new CompilerOptions(Map.of(
                CompilerOptions.OPTION_Compliance, "21", CompilerOptions.OPTION_Source, "21", CompilerOptions.OPTION_TargetPlatform, "21",
                CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.WARNING));
        var compiler = new Compiler(environment, DefaultErrorHandlingPolicies.proceedWithAllProblems(), options,
                result -> accept(result, sources), new DefaultProblemFactory(Locale.ENGLISH));
        compiler.compile(units);
    }

    private void accept(CompilationResult result, Map<String, String> sources) {
        var path = new String(result.getFileName());
        var text = sources.get(path);
        if (text == null) return;
        var lines = lineStarts(text);
        var problems = new ArrayList<Diagnostic>();
        for (var problem : result.getAllProblems() == null ? new CategorizedProblem[0] : result.getAllProblems()) {
            if (!problem.isError() && !problem.isWarning()) continue;
            problems.add(diagnostic(problem, path, text, lines));
        }
        problems.sort(java.util.Comparator.comparingInt(Diagnostic::line).thenComparingInt(Diagnostic::column));
        var classes = new HashMap<String, byte[]>();
        for (var file : result.getClassFiles()) {
            classes.put(Arrays.stream(file.getCompoundName()).map(String::new).collect(Collectors.joining(".")), file.getBytes());
        }
        compiled.put(path, new Compiled(text, List.copyOf(problems), Map.copyOf(classes)));
    }

    private static Diagnostic diagnostic(CategorizedProblem problem, String path, String text, int[] lines) {
        var line = Math.max(1, problem.getSourceLineNumber());
        var start = problem.getSourceStart();
        var end = problem.getSourceEnd();
        var column = start >= 0 && line <= lines.length ? start - lines[line - 1] + 1 : 0;
        var length = start >= 0 && end >= start ? end - start + 1 : 0;
        var symbol = start >= 0 && end >= start && end < text.length() ? symbol(text.substring(start, end + 1)) : "";
        var name = NAMES.getOrDefault(problem.getID(), String.valueOf(problem.getID() & IProblem.IgnoreCategoriesMask));
        return new Diagnostic("ecj." + name, path, line, symbol, problem.getMessage(),
                problem.isError() ? Diagnostic.Severity.ERROR : Diagnostic.Severity.WARNING, column, length);
    }

    /** What a problem's text is about: its last name, the one a member access or an import ends with. */
    private static String symbol(String text) {
        var call = text.indexOf('(');
        var name = (call < 0 ? text : text.substring(0, call)).strip();
        if (name.contains("<")) name = name.substring(0, name.indexOf('<'));
        var dot = name.lastIndexOf('.');
        name = dot < 0 ? name : name.substring(dot + 1);
        return name.chars().allMatch(Character::isJavaIdentifierPart) && !name.isEmpty() ? name : text;
    }

    private static int[] lineStarts(String text) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (var i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The types whose shape changed between two compilations of a file: came, went, or changed structurally. */
    private static Set<String> changed(Map<String, byte[]> before, Map<String, byte[]> after) {
        var changed = new HashSet<String>();
        for (var type : union(before.keySet(), after.keySet())) {
            var old = before.get(type);
            var now = after.get(type);
            try {
                if (old == null || now == null || new ClassFileReader(now, type.toCharArray()).hasStructuralChanges(old)) changed.add(type);
            } catch (ClassFormatException unreadable) {
                changed.add(type);
            }
        }
        return changed;
    }

    /** Whether a text names one of the types: by its simple name, the outermost one's for a nested type. */
    private static boolean names(String text, Set<String> types) {
        for (var type : types) {
            var simple = type.substring(type.lastIndexOf('.') + 1);
            simple = simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
            if (text.contains(simple)) return true;
        }
        return false;
    }

    private Map<String, Set<String>> typesByFile() {
        var types = new HashMap<String, Set<String>>();
        compiled.forEach((path, result) -> types.put(path, result.classes().keySet()));
        return types;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    /** The JDK's and the libraries' types, read once. */
    private FileSystem libraries() {
        if (libraries == null) {
            var paths = Stream.concat(Stream.of(Path.of(System.getProperty("java.home"), "lib", "jrt-fs.jar")),
                    classpath.stream().filter(Files::exists)).map(Path::toString).toArray(String[]::new);
            libraries = new FileSystem(paths, new String[0], "UTF-8");
        }
        return libraries;
    }

    /**
     * Where the compiler finds a type: a workspace type being compiled is the compiler's own; one compiled before, its
     * class file; one never compiled, its text; anything else, the JDK's or a library's.
     */
    private final class Environment implements INameEnvironment {

        private final Set<String> compiling;
        private final Map<String, String> sources;
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, String> files = new HashMap<>();
        private final Set<String> packages = new HashSet<>();

        Environment(Set<String> compiling, Map<String, String> sources) {
            this.compiling = compiling;
            this.sources = sources;
            compiled.forEach((path, result) -> {
                if (!compiling.contains(path) && sources.containsKey(path)) classes.putAll(result.classes());
            });
            for (var path : sources.keySet()) {
                var slash = path.lastIndexOf('/');
                var pkg = slash < 0 ? "" : path.substring(0, slash).replace('/', '.');
                files.put((pkg.isEmpty() ? "" : pkg + ".") + path.substring(slash + 1, path.length() - ".java".length()), path);
                for (var at = pkg; !at.isEmpty(); at = at.contains(".") ? at.substring(0, at.lastIndexOf('.')) : "") packages.add(at);
            }
        }

        @Override
        public NameEnvironmentAnswer findType(char[][] compoundName) {
            var qualified = Arrays.stream(compoundName).map(String::new).collect(Collectors.joining("."));
            var bytes = classes.get(qualified);
            if (bytes != null) {
                try {
                    return new NameEnvironmentAnswer(new ClassFileReader(bytes, qualified.toCharArray()), null);
                } catch (ClassFormatException unreadable) {
                    return null;
                }
            }
            var path = files.get(qualified);
            if (path != null) {
                return compiling.contains(path) ? null
                        : new NameEnvironmentAnswer(new CompilationUnit(sources.get(path).toCharArray(), path, "UTF-8"), null);
            }
            return libraries().findType(compoundName);
        }

        @Override
        public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName) {
            var compound = Arrays.copyOf(packageName, packageName.length + 1);
            compound[packageName.length] = typeName;
            return findType(compound);
        }

        @Override
        public boolean isPackage(char[][] parentPackageName, char[] packageName) {
            var parent = parentPackageName == null ? "" : Arrays.stream(parentPackageName).map(String::new).collect(Collectors.joining("."));
            var name = (parent.isEmpty() ? "" : parent + ".") + new String(packageName);
            return packages.contains(name) || libraries().isPackage(parentPackageName, packageName);
        }

        @Override
        public void cleanup() {
        }
    }

    private static Map<Integer, String> names() {
        var names = new HashMap<Integer, String>();
        for (var field : IProblem.class.getFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) continue;
            try {
                names.putIfAbsent(field.getInt(null), field.getName());
            } catch (IllegalAccessException unreadable) {
                // every field of the interface is public
            }
        }
        return names;
    }
}
