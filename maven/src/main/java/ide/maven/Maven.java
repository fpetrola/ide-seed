package ide.maven;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * What Maven says about a project: the classpath its code compiles and its tests run against. It is asked to Maven
 * itself, which is the authority on it (parents, imported BOMs, exclusions, version mediation), and remembered while
 * the project's POMs do not change, since asking takes seconds. In a project of several modules, it is every module's.
 */
public final class Maven {

    /** The jars and folders of classes of a classpath, and what went wrong getting it, if anything. */
    public record Classpath(List<Path> entries, String problem) {

        public Classpath {
            entries = List.copyOf(entries);
        }

        /** No classpath, and why. */
        public static Classpath none(String why) {
            return new Classpath(List.of(), why);
        }
    }

    /** How the classpath was asked: one remembered before each module was asked apart has only the last one's. */
    private static final String ASKED = "asked each module apart";

    private static final String PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.7.0";
    private static final Set<String> BUILT = Set.of("target", "build", "out");
    private static final long MINUTES = 10;

    private final Path folder;

    private Maven(Path folder) {
        this.folder = folder;
    }

    /** The project a folder is in: the nearest folder at or above it with a {@code pom.xml}. */
    public static Optional<Maven> project(Path folder) {
        for (var at = folder.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("pom.xml"))) return Optional.of(new Maven(at));
        }
        return Optional.empty();
    }

    /** Where the project's {@code pom.xml} is. */
    public Path folder() {
        return folder;
    }

    /**
     * The classpath, as remembered in {@code cache} if the POMs are the ones it was asked with, and it was asked as it is
     * now; else asked again.
     */
    public Classpath classpath(Path cache) {
        var poms = poms();
        try {
            if (Files.isRegularFile(cache)) {
                var lines = Files.readAllLines(cache);
                if (lines.contains(ASKED) && lines.stream().filter(line -> line.startsWith("pom ")).toList().equals(poms)) {
                    return remembered(lines);
                }
            }
            var classpath = ask();
            var lines = new ArrayList<>(List.of(ASKED));
            lines.addAll(poms);
            classpath.entries().forEach(entry -> lines.add("entry " + entry));
            if (classpath.problem() != null) classpath.problem().lines().forEach(line -> lines.add("problem " + line));
            Files.createDirectories(cache.getParent());
            Files.write(cache, lines);
            return classpath;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Classpath remembered(List<String> lines) {
        var entries = lines.stream().filter(line -> line.startsWith("entry ")).map(line -> Path.of(line.substring(6))).toList();
        var problem = String.join("\n", lines.stream().filter(line -> line.startsWith("problem ")).map(line -> line.substring(8)).toList());
        return new Classpath(entries, problem.isEmpty() ? null : problem);
    }

    /**
     * Runs Maven in the project's folder: each module's classpath with its tests', each in a file of its own (the goal
     * cannot be told from the command line to append, and a reactor's modules would overwrite one file: only the last
     * one's would be left).
     */
    private Classpath ask() throws IOException {
        var outputs = Files.createTempDirectory("classpath");
        try {
            var process = new ProcessBuilder(executable(), "-B", "-q", "-fae", PLUGIN + ":build-classpath",
                    "-Dmdep.outputFile=" + outputs.resolve("${project.groupId}.${project.artifactId}.txt"), "-DincludeScope=test")
                    .directory(folder.toFile()).redirectErrorStream(true).start();
            var said = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return Classpath.none("Maven did not answer in " + MINUTES + " minutes");
            }
            var entries = new LinkedHashSet<Path>();
            try (var files = Files.list(outputs)) {
                for (var file : files.sorted().toList()) {
                    Arrays.stream(Files.readString(file).split(File.pathSeparator)).map(String::strip)
                            .filter(entry -> !entry.isEmpty()).map(Path::of).forEach(entries::add);
                }
            }
            return new Classpath(List.copyOf(entries), process.exitValue() == 0 ? null : errors(said));
        } catch (IOException notThere) {
            return Classpath.none("Maven could not be run (is mvn on the PATH?): " + notThere.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Classpath.none("interrupted while Maven ran");
        } finally {
            try (var files = Files.list(outputs)) {
                for (var file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(outputs);
        }
    }

    /** What Maven said went wrong: its errors, or its last lines if it marked none. */
    private static String errors(String said) {
        var lines = said.lines().toList();
        var errors = lines.stream().filter(line -> line.contains("[ERROR]")).limit(5).toList();
        return String.join("\n", errors.isEmpty() ? lines.subList(Math.max(0, lines.size() - 5), lines.size()) : errors);
    }

    /**
     * What the classpath depends on: every POM of the project and of the folders above it that have one, each with
     * its size and when it changed.
     */
    private List<String> poms() {
        var poms = new ArrayList<Path>();
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    return !dir.equals(folder) && (BUILT.contains(name) || name.startsWith("."))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.getFileName().toString().equals("pom.xml")) poms.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            for (var above = folder.getParent(); above != null && Files.isRegularFile(above.resolve("pom.xml")); above = above.getParent()) {
                poms.add(above.resolve("pom.xml"));
            }
            var lines = new ArrayList<String>();
            for (var pom : poms.stream().sorted().toList()) {
                lines.add("pom " + pom + " " + Files.size(pom) + " " + Files.getLastModifiedTime(pom).toMillis());
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String executable() {
        return System.getProperty("os.name", "").startsWith("Windows") ? "mvn.cmd" : "mvn";
    }
}
