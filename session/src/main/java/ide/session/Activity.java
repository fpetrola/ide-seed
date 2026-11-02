package ide.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What an agent just did with one of its tools, said in a few words, as a step of the task it is doing: the tool and
 * what it worked on (a description it gave, a file, a command, a pattern), paths in the project said from its folder.
 * The IDE's own tools are said with their arguments; the ones that keep the plans or attention are not steps.
 */
final class Activity {

    private static final String IDE = "mcp__ideseed__";
    private static final List<String> SAID_BY = List.of("description", "file_path", "path", "notebook_path", "command", "pattern",
            "url", "query", "prompt");
    private static final int LONGEST = 120;

    private Activity() {
    }

    /** The step an event of the agent's tools is, if it is one. */
    static Optional<String> step(Map<?, ?> event, String folder) {
        var tool = String.valueOf(event.get("tool_name"));
        var input = event.get("tool_input") instanceof Map<?, ?> given ? given : Map.of();
        if (tool.startsWith(IDE)) {
            var name = tool.substring(IDE.length());
            if (List.of("plan", "task", "steer", "guide", "activity").stream().anyMatch(name::startsWith)) return Optional.empty();
            var args = input.entrySet().stream().map(e -> e.getKey() + "=" + inProject(String.valueOf(e.getValue()), folder))
                    .collect(Collectors.joining(" "));
            return Optional.of(shortened(args.isEmpty() ? name : name + " " + args));
        }
        if (tool.startsWith("mcp__") || tool.equals("TodoWrite")) return Optional.empty();
        var what = SAID_BY.stream().map(input::get).filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(value -> inProject(String.valueOf(value), folder)).findFirst();
        var separator = input.containsKey("description") || input.containsKey("command") ? ": " : " ";
        return Optional.of(shortened(what.map(w -> tool + separator + w).orElse(tool)));
    }

    private static String inProject(String text, String folder) {
        return folder.isEmpty() ? text : text.replace(folder.endsWith("/") ? folder : folder + "/", "");
    }

    private static String shortened(String text) {
        var line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= LONGEST ? line : line.substring(0, LONGEST - 1) + "…";
    }
}
