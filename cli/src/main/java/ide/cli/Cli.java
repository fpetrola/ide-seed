package ide.cli;

import ide.session.Workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The IDE from a terminal, over the project in a folder. {@code ide <folder> <capability> [args]} runs one command and
 * saves what it changed; {@code ide <folder> --script <file>} runs a file of commands, one per line, until one fails;
 * {@code ide <folder>} reads commands until {@code exit}. In a script or the shell, {@code save} saves. Every command
 * is a capability, and {@code help} shows each with its schema.
 */
public final class Cli {

    private static final String USAGE = "usage: ide <folder> [<capability> [args...] | --script <file>]";

    private final BufferedReader in;
    private final PrintWriter out;

    public Cli(BufferedReader in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    /** Runs what the arguments say; answers 0, or 1 when a command failed (2 without a folder). */
    public int run(String... args) {
        if (args.length == 0) {
            out.println(USAGE);
            return 2;
        }
        var workspace = Workspace.open(Path.of(args[0]));
        var words = List.of(args).subList(1, args.length);
        if (words.isEmpty()) return shell(workspace);
        if (words.getFirst().equals("--script")) return script(workspace, Path.of(words.get(1)));
        if (!execute(workspace, words)) return 1;
        workspace.capabilities().invoke("save", java.util.Map.of());
        return 0;
    }

    private int shell(Workspace workspace) {
        try {
            while (true) {
                out.print("ide> ");
                out.flush();
                var line = in.readLine();
                if (line == null) return 0;
                var words = Command.words(line);
                if (words.isEmpty()) continue;
                if (words.getFirst().equals("exit") || words.getFirst().equals("quit")) return 0;
                execute(workspace, words);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private int script(Workspace workspace, Path file) {
        try {
            for (var line : Files.readAllLines(file)) {
                var words = Command.words(line);
                if (words.isEmpty() || words.getFirst().startsWith("#")) continue;
                out.println("ide> " + line.strip());
                if (!execute(workspace, words)) return 1;
            }
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One command: its answer as text, or what went wrong. */
    private boolean execute(Workspace workspace, List<String> words) {
        try {
            var capabilities = workspace.capabilities();
            if (words.getFirst().equals("help")) {
                var ids = words.size() > 1 ? words.subList(1, words.size()) : capabilities.ids();
                ids.forEach(id -> out.println(capabilities.help(id)));
            } else {
                var command = Command.of(words, capabilities);
                out.print(Text.of(capabilities.invoke(command.capability(), command.args())));
            }
            out.flush();
            return true;
        } catch (RuntimeException failed) {
            out.println("error: " + failed.getMessage());
            return false;
        }
    }
}
