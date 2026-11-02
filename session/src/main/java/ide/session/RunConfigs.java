package ide.session;

import ide.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * How programs are run, kept by name, as IntelliJ keeps its run configurations: the class a program runs from, its
 * arguments, the virtual machine's options (both written as a shell writes them), the folder it runs in (from the
 * project's, if relative; the project's, if none) and its environment ({@code NAME=value;...}). One of them is the
 * chosen one. Kept in a file with the project, if there is one; each change is an event. Knows nothing of programs:
 * whether a class runs, the workspace says.
 */
final class RunConfigs {

    record Config(String name, String type, String args, String vm, String directory, String environment) {

        Map<String, Object> shown() {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("name", name);
            shown.put("type", type);
            shown.put("args", args);
            shown.put("vm", vm);
            shown.put("directory", directory);
            shown.put("environment", environment);
            return shown;
        }

        /** The program's arguments, one by one. */
        List<String> arguments() {
            return words(args);
        }

        /** The virtual machine's options, one by one. */
        List<String> options() {
            return words(vm);
        }

        /** The environment's variables, by name. */
        Map<String, String> variables() {
            var variables = new LinkedHashMap<String, String>();
            for (var pair : environment.split(";")) {
                if (pair.isBlank()) continue;
                var equals = pair.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("the environment is NAME=value;...: not " + pair.strip());
                variables.put(pair.substring(0, equals).strip(), pair.substring(equals + 1));
            }
            return variables;
        }
    }

    private final Path file;
    private final Consumer<Map<String, Object>> events;
    private final List<Config> configs = new ArrayList<>();
    private String chosen = "";

    RunConfigs(Path file, Consumer<Map<String, Object>> events) {
        this.file = file;
        this.events = events;
        if (file != null && Files.exists(file)) read();
    }

    /** Keeps a configuration, in place of the one of its name if there was one; it is the chosen one from now on. */
    Config set(Config config) {
        if (config.name().isBlank()) throw new IllegalArgumentException("a run configuration needs a name");
        config.arguments();
        config.options();
        config.variables();
        var at = indexOf(config.name());
        if (at < 0) configs.add(config);
        else configs.set(at, config);
        chosen = config.name();
        changed(Map.of("event", "run-config", "name", config.name(), "type", config.type()));
        return config;
    }

    void remove(String name) {
        var at = indexOf(name);
        if (at < 0) throw new IllegalArgumentException("no run configuration " + name);
        configs.remove(at);
        if (chosen.equals(name)) chosen = configs.isEmpty() ? "" : configs.getFirst().name();
        changed(Map.of("event", "run-config", "name", name, "removed", true));
    }

    Config get(String name) {
        var at = indexOf(name);
        if (at < 0) throw new IllegalArgumentException("no run configuration " + name);
        return configs.get(at);
    }

    void choose(String name) {
        get(name);
        if (chosen.equals(name)) return;
        chosen = name;
        save();
    }

    Optional<Config> chosen() {
        return chosen.isEmpty() ? Optional.empty() : Optional.of(get(chosen));
    }

    /** The configuration of a class: the first that runs it, or a new one named after it, with nothing else said. */
    Config of(String type) {
        for (var config : configs) {
            if (config.type().equals(type)) return config;
        }
        var simple = type.substring(type.lastIndexOf('.') + 1);
        var name = simple;
        for (var n = 2; indexOf(name) >= 0; n++) name = simple + " (" + n + ")";
        return set(new Config(name, type, "", "", "", ""));
    }

    Map<String, Object> shown() {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("configs", configs.stream().map(Config::shown).toList());
        shown.put("chosen", chosen);
        return shown;
    }

    private int indexOf(String name) {
        for (var i = 0; i < configs.size(); i++) if (configs.get(i).name().equals(name)) return i;
        return -1;
    }

    /** Words as a shell reads them: apart where there is space, together inside quotes; a backslash keeps what follows. */
    static List<String> words(String text) {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        var inWord = false;
        char quote = 0;
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < text.length()) word.append(text.charAt(++i));
                else word.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < text.length()) {
                word.append(text.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) words.add(word.toString());
                word.setLength(0);
                inWord = false;
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("a quote is not closed in: " + text);
        if (inWord) words.add(word.toString());
        return words;
    }

    private void changed(Map<String, Object> event) {
        save();
        events.accept(event);
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(shown()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the run configurations in " + file, e);
        }
    }

    private void read() {
        try {
            var kept = (Map<?, ?>) Json.read(Files.readString(file));
            for (var c : (List<?>) kept.get("configs")) {
                var config = (Map<?, ?>) c;
                configs.add(new Config(text(config, "name"), text(config, "type"), text(config, "args"), text(config, "vm"),
                        text(config, "directory"), text(config, "environment")));
            }
            chosen = text(kept, "chosen");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the run configurations in " + file, e);
        }
    }

    private static String text(Map<?, ?> map, String name) {
        return map.get(name) == null ? "" : String.valueOf(map.get(name));
    }
}
