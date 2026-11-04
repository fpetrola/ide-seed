package ide.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * How the window shows what it shows, each choice by name ({@code flatten-packages}, {@code underline-errors}), kept
 * from one window to the next in a file ({@code ~/.ideseed/shown.properties}; another folder with
 * {@code -Dide.ui.home}, as the tests do).
 */
final class Shown {

    private final Path file;
    private final Properties chosen = new Properties();

    Shown(Path file) {
        this.file = file;
        try (var in = Files.newBufferedReader(file)) {
            chosen.load(in);
        } catch (IOException none) {
            // nothing chosen yet: each as it usually is
        }
    }

    /** Where the window keeps them. */
    static Shown remembered() {
        var home = System.getProperty("ide.ui.home");
        return new Shown((home == null ? Path.of(System.getProperty("user.home"), ".ideseed") : Path.of(home)).resolve("shown.properties"));
    }

    boolean is(String choice, boolean usually) {
        var value = chosen.getProperty(choice);
        return value == null ? usually : Boolean.parseBoolean(value);
    }

    void set(String choice, boolean on) {
        chosen.setProperty(choice, String.valueOf(on));
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newBufferedWriter(file)) {
                chosen.store(out, "how IdeSeed's window shows things");
            }
        } catch (IOException unwritable) {
            // the next window shows it as usual
        }
    }
}
