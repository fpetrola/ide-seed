package ide.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** {@code java -jar ide-cli.jar <folder> ...}: see {@link Cli}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        System.exit(new Cli(in, out).run(args));
    }
}
