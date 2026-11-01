package ide.mcp;

import ide.session.Workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** {@code java -jar ide-mcp.jar <folder>}: the project in the folder, served on stdio (see {@link Server}). */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ide-mcp <folder>");
            System.exit(2);
        }
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        new Server(Workspace.open(Path.of(args[0]))).run(in, out);
    }
}
