package ide.ui;

import javax.swing.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * Tells the window that something changed under the project's folder, once things have been quiet for a moment, on
 * Swing's thread: what changed, and whether it matters, the IDE decides (sync). Folders that builds and tools write,
 * and hidden ones, are not watched.
 */
final class Watcher {

    private static final Set<String> BUILT = Set.of("target", "build", "out");

    private final WatchService service;
    private final Path root;
    private final Timer quiet;
    private final Thread thread;

    Watcher(Path root, Runnable changed) {
        this.root = root;
        this.quiet = new Timer(250, event -> changed.run());
        quiet.setRepeats(false);
        try {
            this.service = FileSystems.getDefault().newWatchService();
            register(root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot watch " + root, e);
        }
        this.thread = new Thread(this::watch, "watch " + root);
        thread.setDaemon(true);
        thread.start();
    }

    private void register(Path folder) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                var name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && (BUILT.contains(name) || name.startsWith("."))) return FileVisitResult.SKIP_SUBTREE;
                dir.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watch() {
        try {
            while (true) {
                WatchKey key = service.take();
                var folder = (Path) key.watchable();
                for (var event : key.pollEvents()) {
                    if (event.kind() == ENTRY_CREATE && event.context() instanceof Path created
                            && Files.isDirectory(folder.resolve(created))) {
                        register(folder.resolve(created)); // a new package
                    }
                }
                key.reset();
                quiet.restart();
            }
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException stopped) {
            // the window is going away
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void stop() {
        quiet.stop();
        try {
            service.close();
        } catch (IOException ignored) {
            // nothing more will be told
        }
    }
}
