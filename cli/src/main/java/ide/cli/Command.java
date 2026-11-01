package ide.cli;

import ide.core.CapabilityRegistry;
import ide.core.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A capability and its arguments, from the words of a line, by the capability's schema: {@code name=value} binds by
 * name, any other word the next parameter in the order the schema declares them. A list of elements is written with
 * commas, a truth as true or false, and {@code param.name=value} is one entry of a parameter that takes several by
 * name: the arguments of the refactoring a preview is about, the answers to what a fix asks.
 */
record Command(String capability, Map<String, Object> args) {

    /** The words of a line: split at spaces, except inside double quotes. */
    static List<String> words(String line) {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        var quoted = false;
        var started = false;
        for (var c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                started = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (started) words.add(word.toString());
                word.setLength(0);
                started = false;
            } else {
                word.append(c);
                started = true;
            }
        }
        if (started) words.add(word.toString());
        return words;
    }

    static Command of(List<String> words, CapabilityRegistry registry) {
        var capability = registry.get(words.getFirst());
        var params = capability.params();
        var args = new LinkedHashMap<String, Object>();
        var next = 0;
        for (var word : words.subList(1, words.size())) {
            var eq = word.indexOf('=');
            var name = eq > 0 ? word.substring(0, eq) : "";
            var dot = name.indexOf('.');
            if (dot > 0 && params.stream().anyMatch(p -> p.name().equals(name.substring(0, dot)))) {
                entries(args, name.substring(0, dot)).put(name.substring(dot + 1), word.substring(eq + 1));
                continue;
            }
            var named = params.stream().filter(p -> p.name().equals(name)).findFirst();
            if (named.isPresent()) {
                args.put(name, typed(named.get(), word.substring(eq + 1)));
                continue;
            }
            while (next < params.size() && args.containsKey(params.get(next).name())) next++;
            if (next == params.size()) throw new IllegalArgumentException("too many arguments for " + capability.id());
            args.put(params.get(next).name(), typed(params.get(next), word));
        }
        return new Command(capability.id(), args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entries(Map<String, Object> args, String param) {
        return (Map<String, Object>) args.computeIfAbsent(param, p -> new LinkedHashMap<String, Object>());
    }

    private static Object typed(Param param, String value) {
        return switch (param.type()) {
            case "elements" -> Arrays.stream(value.split(",")).map(String::strip).filter(e -> !e.isEmpty()).toList();
            case "bool" -> Boolean.parseBoolean(value);
            default -> value;
        };
    }
}
