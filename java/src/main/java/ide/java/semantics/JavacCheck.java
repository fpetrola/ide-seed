package ide.java.semantics;

import com.sun.source.util.JavacTask;
import ide.projection.Diagnostic;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.tools.StandardLocation;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs javac over in-memory sources (attribution only, nothing written) against a classpath, and reports errors as
 * {@code javac.<code>}.
 */
public final class JavacCheck {

    private JavacCheck() {
    }

    public static List<Diagnostic> errors(Map<String, String> sources, List<Path> classpath) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        var files = sources.entrySet().stream().map(e -> source(e.getKey(), e.getValue())).toList();
        var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try {
            // always the project's classpath, even none: by default javac would read the IDE's own; and every error,
            // the flow's too (a final left unset) when some name does not resolve
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath.stream().filter(Files::exists).toList());
            var task = (JavacTask) compiler.getTask(null, fileManager, collector, List.of("-proc:none", "-Xlint:none", "-XDshould-stop.ifError=FLOW"), null, files);
            task.analyze();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        var errors = new ArrayList<Diagnostic>();
        for (var d : collector.getDiagnostics()) {
            if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR || d.getSource() == null) continue;
            var path = d.getSource().toUri().getPath().substring(1);
            var code = sources.get(path);
            var symbol = symbolAt(code, d.getPosition(), d.getStartPosition(), d.getEndPosition());
            errors.add(new Diagnostic("javac." + d.getCode().replaceFirst("^compiler\\.err\\.", ""), path,
                    (int) d.getLineNumber(), symbol, d.getMessage(null)));
        }
        return errors;
    }

    /**
     * What a diagnostic is about: the identifier at its preferred position (after the dot, where javac points at a
     * member selected as in {@code v.size}), else the text of its range.
     */
    private static String symbolAt(String code, long position, long start, long end) {
        if (position >= 0 && position < code.length() && code.charAt((int) position) == '.') {
            do position++; while (position < code.length() && Character.isWhitespace(code.charAt((int) position)));
        }
        if (position >= 0 && position < code.length() && Character.isJavaIdentifierStart(code.charAt((int) position))) {
            var stop = (int) position;
            while (stop < code.length() && Character.isJavaIdentifierPart(code.charAt(stop))) stop++;
            return code.substring((int) position, stop);
        }
        return start >= 0 && end > start ? code.substring((int) start, (int) end) : "";
    }

    private static JavaFileObject source(String path, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }
}
