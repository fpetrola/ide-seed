package ide.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A project's classpath, as Maven resolves it; asked once, and again only when a POM changes. */
class MavenTest {

    @TempDir
    Path folder;

    private static String pom(String dependency) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>t</groupId>
                    <artifactId>t</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            %s
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(dependency);
    }

    private static final String JAVAPARSER = "<groupId>com.github.javaparser</groupId><artifactId>javaparser-core</artifactId><version>3.26.2</version>";

    @Test
    void theProjectIsTheNearestFolderWithAPom() throws IOException {
        Files.writeString(folder.resolve("pom.xml"), pom(JAVAPARSER));
        var sources = Files.createDirectories(folder.resolve("src/main/java"));

        assertThat(Maven.project(sources).map(Maven::folder)).contains(folder.toAbsolutePath().normalize());
        assertThat(Maven.project(Files.createTempDirectory("no-project"))).isEmpty();
    }

    @Test
    void theClasspathIsAskedToMavenAndRememberedWhileThePomsAreTheSame() throws IOException {
        Files.writeString(folder.resolve("pom.xml"), pom(JAVAPARSER));
        var cache = folder.resolve(".ide/classpath.txt");
        var maven = Maven.project(folder).orElseThrow();

        var classpath = maven.classpath(cache);

        assertThat(classpath.problem()).isNull();
        assertThat(classpath.entries()).anyMatch(jar -> jar.getFileName().toString().equals("javaparser-core-3.26.2.jar"));
        Files.writeString(cache, Files.readString(cache).replace("javaparser-core-3.26.2.jar", "remembered.jar"));
        assertThat(maven.classpath(cache).entries()).anyMatch(jar -> jar.getFileName().toString().equals("remembered.jar"));

        Files.writeString(folder.resolve("pom.xml"), pom(JAVAPARSER.replace("javaparser-core", "javaparser-symbol-solver-core")));

        assertThat(maven.classpath(cache).entries()).extracting(jar -> jar.getFileName().toString())
                .contains("javaparser-symbol-solver-core-3.26.2.jar", "javaparser-core-3.26.2.jar").doesNotContain("remembered.jar");
    }

    /** Each module's, with its tests': the plugin writes one file per module, which a reactor would overwrite. */
    @Test
    void theClasspathOfAReactorIsEveryModulesWithItsTests() throws IOException {
        Files.writeString(folder.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>t</groupId>
                    <artifactId>root</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>a</module>
                        <module>b</module>
                    </modules>
                </project>
                """);
        Files.createDirectories(folder.resolve("a"));
        Files.createDirectories(folder.resolve("b"));
        Files.writeString(folder.resolve("a/pom.xml"), pom(JAVAPARSER + "<scope>test</scope>").replace("<artifactId>t</artifactId>", "<artifactId>a</artifactId>"));
        Files.writeString(folder.resolve("b/pom.xml"), pom("<groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version>")
                .replace("<artifactId>t</artifactId>", "<artifactId>b</artifactId>"));

        var cache = folder.resolve(".ide/classpath.txt");
        var maven = Maven.project(folder).orElseThrow();
        var classpath = maven.classpath(cache);

        assertThat(classpath.problem()).isNull();
        assertThat(classpath.entries()).extracting(jar -> jar.getFileName().toString())
                .contains("javaparser-core-3.26.2.jar", "junit-4.13.2.jar");

        // one remembered before modules were asked apart had only the last one's: it is asked again
        Files.write(cache, Files.readAllLines(cache).stream().filter(line -> line.startsWith("pom ")).toList());
        assertThat(maven.classpath(cache).entries()).extracting(jar -> jar.getFileName().toString()).contains("junit-4.13.2.jar");
    }

    @Test
    void whatMavenCannotResolveIsSaid() throws IOException {
        Files.writeString(folder.resolve("pom.xml"), pom("<groupId>does.not</groupId><artifactId>exist</artifactId><version>1</version>"));

        var classpath = Maven.project(folder).orElseThrow().classpath(folder.resolve(".ide/classpath.txt"));

        assertThat(classpath.entries()).isEmpty();
        assertThat(classpath.problem()).contains("does.not:exist");
    }
}
