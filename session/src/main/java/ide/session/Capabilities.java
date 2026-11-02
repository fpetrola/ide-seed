package ide.session;

import ide.core.Capability;
import ide.core.CapabilityRegistry;
import ide.core.Param;
import ide.java.JavaProjection;
import ide.refactoring.Refactoring;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a workspace can do, as capabilities for the adapters: each refactoring in its library, and each of its
 * operations, with a schema. Answers are what the operations answer, in a form JSON can write.
 */
final class Capabilities {

    private Capabilities() {
    }

    static CapabilityRegistry of(Workspace workspace, Collection<Refactoring> refactorings) {
        var registry = new CapabilityRegistry();
        for (var refactoring : refactorings) {
            registry.register(Capability.of(refactoring.id(), refactoring.description(), refactoring.params(),
                    args -> Session.report(workspace.invoke(refactoring.id(), args))));
        }
        var path = Param.of("path", "text");
        var line = Param.of("line", "text");
        var refactoring = Param.of("refactoring", "text");
        var itsArgs = Param.of("args", "map");
        return registry
                .register(Capability.of("guide", "How an agent works on this IDE with a person: the plan, where each looks, the tools to change code",
                        List.of(), args -> guide()))
                .register(Capability.of("ls", "Source files", List.of(), args -> workspace.files().stream().sorted().toList()))
                .register(Capability.of("show", "Source of a file", List.of(path), args -> workspace.source(text(args, "path"))))
                .register(Capability.of("diff", "Net diff of unsaved changes", List.of(), args -> workspace.diff()))
                .register(Capability.of("undo", "Reverts the last applied transaction", List.of(), args -> {
                    workspace.undo();
                    return "ok";
                }))
                .register(Capability.of("classpath", "The project's libraries besides the JDK, and what went wrong asking Maven for them",
                        List.of(), args -> entries("entries", workspace.classpath().entries().stream().map(Object::toString).toList(),
                                "problem", workspace.classpath().problem())))
                .register(Capability.of("save", "Writes the changed files where the project was opened from", List.of(), args -> {
                    workspace.save();
                    return "ok";
                }))
                .register(Capability.of("sync", "Takes what changed on disk since it was read or saved, each file as an edit by hand; "
                        + "a file the IDE changed too is a conflict left alone", List.of(), args -> {
                    var sync = workspace.sync();
                    return entries("taken", sync.taken(), "conflicts", sync.conflicts(), "refused", sync.refused(),
                            "deleted", sync.deleted());
                }))
                .register(Capability.of("plans", "Every plan, with its state (active, paused, done, dropped) and how many of "
                        + "its tasks are finished", List.of(), args -> workspace.plans().list()))
                .register(Capability.of("plan", "A plan (the active one if none is said) with its tasks, depth first: id, title, "
                        + "state, parent, depth, how many subtasks are finished, and what each still waits for",
                        List.of(Param.of("plan", "text").optional()), args -> workspace.plans().plan(optional(args, "plan"))))
                .register(Capability.of("plan-new", "Makes a plan the active one (the one that was waits, paused): a title, what "
                        + "it is for, and its tasks one per line, each indented under the line of action it is part of",
                        List.of(Param.of("title", "text"), Param.of("description", "text").optional(), Param.of("tasks", "text")),
                        args -> workspace.plans().create(text(args, "title"), optional(args, "description").orElse(""), text(args, "tasks"))))
                .register(Capability.of("plan-switch", "Goes on with another plan; the active one waits, paused",
                        List.of(Param.of("plan", "text")), args -> {
                    workspace.plans().activate(text(args, "plan"));
                    return workspace.plans().plan(Optional.empty());
                }))
                .register(Capability.of("plan-state", "Says how a plan is: active, paused, done or dropped",
                        List.of(Param.of("plan", "text"), Param.of("state", "choice", "active", "paused", "done", "dropped")), args -> {
                    workspace.plans().state(text(args, "plan"), Plans.PlanState.valueOf(text(args, "state").toUpperCase(java.util.Locale.ROOT)));
                    return workspace.plans().plan(Optional.of(text(args, "plan")));
                }))
                .register(Capability.of("task", "A task with everything it has: plan, title, description, state, parent, subtasks, "
                        + "what it waits for, links into the code and its timeline", List.of(Param.of("task", "text")),
                        args -> workspace.plans().task(optional(args, "task"))))
                .register(Capability.of("task-add", "Adds a task to do: at the end of a line of action (parent), or of a plan (the "
                        + "active one if none is said); after: tasks it waits for, comma-separated",
                        List.of(Param.of("title", "text"), Param.of("description", "text").optional(), Param.of("parent", "text").optional(),
                                Param.of("plan", "text").optional(), Param.of("after", "text").optional(), by()),
                        args -> workspace.plans().add(optional(args, "plan"), text(args, "title"), optional(args, "description").orElse(""),
                                optional(args, "parent"), optional(args, "after").map(a -> List.of(a.split("\\s*,\\s*"))).orElse(List.of()),
                                who(args))))
                .register(Capability.of("task-update", "Changes a task: its state (todo, doing, blocked, paused, done, dropped), a "
                        + "note for its timeline, title, description, parent (none: a line of action) or what it waits for (after, "
                        + "comma-separated; none: nothing)", List.of(Param.of("task", "text"),
                        Param.of("state", "choice", "todo", "doing", "blocked", "paused", "done", "dropped").optional(),
                        Param.of("note", "text").optional(), Param.of("title", "text").optional(), Param.of("description", "text").optional(),
                        Param.of("parent", "text").optional(), Param.of("after", "text").optional(), by()), args -> {
                    var changes = new java.util.LinkedHashMap<String, Optional<String>>();
                    for (var name : List.of("state", "note", "title", "description", "parent", "after")) changes.put(name, optional(args, name));
                    return workspace.plans().update(text(args, "task"), changes, who(args));
                }))
                .register(Capability.of("task-link", "Links a place of the code to a task: an element (its whole declaration) or "
                        + "lines of a file, line to end, with a note", List.of(Param.of("task", "text"), Param.of("element", "element").optional(),
                        Param.of("path", "text").optional(), Param.of("line", "text").optional(), Param.of("end", "text").optional(),
                        Param.of("note", "text").optional()), args -> {
                    var from = args.containsKey("line") ? number(args, "line") : 0;
                    return workspace.link(text(args, "task"), optional(args, "element"), optional(args, "path"), from,
                            args.containsKey("end") ? number(args, "end") : from, optional(args, "note").orElse(""));
                }))
                .register(Capability.of("steer", "What the person says about a plan or a task (start, pause, resume, stop, comment, "
                        + "replan, switch to a plan): it changes at once, and waits to be told to the agent unless told is true",
                        List.of(Param.of("kind", "choice", "start", "pause", "resume", "stop", "comment", "replan", "switch"), Param.of("plan", "text").optional(),
                                Param.of("task", "text").optional(), Param.of("text", "text").optional(), Param.of("told", "text").optional()),
                        args -> {
                            var steer = workspace.plans().steer(text(args, "kind"), optional(args, "plan"), optional(args, "task"),
                                    optional(args, "text").orElse(""), optional(args, "told").map(Boolean::parseBoolean).orElse(false));
                            return entries("n", steer.n(), "message", steer.message());
                        }))
                .register(Capability.of("activity", "What an agent did with one of its tools (an event as its hooks give it: "
                        + "tool_name, tool_input), kept as a step of the task it is doing; answers the task and the step",
                        List.of(Param.of("event", "map")), args -> {
                    var event = args.get("event") instanceof Map<?, ?> given ? given : Map.of();
                    if (event.get("session_id") != null) {
                        workspace.conversation().in(String.valueOf(event.get("session_id")),
                                event.get("transcript_path") == null ? null : String.valueOf(event.get("transcript_path")));
                    }
                    var folder = workspace.root().map(r -> r.toAbsolutePath().normalize().toString()).orElse("");
                    return Activity.step(event, folder).map(step -> workspace.plans().step(step))
                            .orElseGet(() -> entries("task", "", "step", ""));
                }))
                .register(Capability.of("conversation", "The agent's conversation on this project, to go on with it: its id "
                        + "(none yet: empty), and whether it was open when the IDE closed", List.of(),
                        args -> workspace.conversation().shown()))
                .register(Capability.of("conversation-in", "Says which conversation the agent goes on with, and where it is written",
                        List.of(Param.of("id", "text"), Param.of("transcript", "text").optional()), args -> {
                    workspace.conversation().in(text(args, "id"), optional(args, "transcript").orElse(null));
                    return workspace.conversation().shown();
                }))
                .register(Capability.of("task-talk", "What was said while a task was being done: what the person said, what the "
                        + "agent answered and the tools it used, in order, read from the agent's conversation",
                        List.of(Param.of("task", "text")), args -> entries("task", text(args, "task"),
                                "messages", workspace.talk(text(args, "task")))))
                .register(Capability.of("conversation-open", "Says whether the agent's conversation is open as the IDE closes",
                        List.of(Param.of("open", "choice", "true", "false")), args -> {
                    workspace.conversation().open(Boolean.parseBoolean(text(args, "open")));
                    return workspace.conversation().shown();
                }))
                .register(Capability.of("steering", "What the person said about the plans that the agent was not told yet; once "
                        + "answered, it is told", List.of(), args -> workspace.plans().take().stream()
                        .map(steer -> entries("n", steer.n(), "message", steer.message())).toList()))
                .register(Capability.of("point", "Shows the person a place in the IDE's window, with a note: an element (where it "
                        + "is declared), or lines of a file, line to end", List.of(Param.of("element", "element").optional(),
                        Param.of("path", "text").optional(), Param.of("line", "text").optional(), Param.of("end", "text").optional(),
                        Param.of("note", "text").optional()), args -> {
                    var from = args.containsKey("line") ? number(args, "line") : 0;
                    return workspace.point(optional(args, "element"), optional(args, "path"), from,
                            args.containsKey("end") ? number(args, "end") : from, optional(args, "note").orElse("")).shown();
                }))
                .register(Capability.of("pointed", "The last place the person was pointed at, numbered from 1 (0: none yet)",
                        List.of(), args -> workspace.attention().pointed().shown()))
                .register(Capability.of("look", "Where the person is looking, as the window says it: a file, the caret, and "
                        + "what is selected (lines first-last, or a stretch), if anything", List.of(path, line,
                        Param.of("column", "text"), Param.of("selected", "text").optional()), args -> {
                    workspace.attention().look(new Attention.Look(text(args, "path"), number(args, "line"), number(args, "column"),
                            optional(args, "selected").orElse("")));
                    return "ok";
                }))
                .register(Capability.of("looking", "Where the person is looking in the IDE's window: the file, the caret's line "
                        + "and column, what is selected, the element under the caret and what it is inside", List.of(), args -> {
                    var look = workspace.attention().looking();
                    var element = look.path().isEmpty() ? Optional.<String>empty()
                            : workspace.at(look.path(), look.line(), look.column()).map(e -> e.qualified());
                    var inside = look.path().isEmpty() ? Optional.<String>empty()
                            : workspace.inside(look.path(), look.line(), look.column()).map(e -> e.qualified());
                    return entries("path", look.path(), "line", look.line(), "column", look.column(), "selected", look.selected(),
                            "element", element.orElse(""), "inside", inside.orElse(""));
                }))
                .register(Capability.of("mains", "The classes a program can be run from (a main method Java runs), of a file if one is said",
                        List.of(Param.of("path", "text").optional()), args -> workspace.mains(optional(args, "path")).stream()
                        .map(main -> entries("type", main.type(), "path", main.path(), "line", main.line(), "methodLine", main.methodLine())).toList()))
                .register(Capability.of("run-command", "How to run a program, as it is saved: by a run configuration, by a class "
                        + "with a main (its configuration, made if it has none), or the chosen configuration; the command, the folder it "
                        + "runs in, its environment, and a title", List.of(Param.of("config", "text").optional(), Param.of("type", "text").optional()),
                        args -> workspace.runCommand(optional(args, "config"), optional(args, "type"))))
                .register(Capability.of("run-configs", "The run configurations, and the chosen one", List.of(), args -> workspace.runs().shown()))
                .register(Capability.of("run-config-set", "Keeps a run configuration (in place of the one of its name): the class with "
                        + "a main it runs, the program's arguments and the virtual machine's options as a shell writes them, the folder "
                        + "it runs in (from the project's), its environment as NAME=value;...", List.of(Param.of("name", "text"),
                        Param.of("type", "choice", workspace.mains(Optional.empty()).stream().map(JavaProjection.Main::type).toArray(String[]::new)),
                        Param.of("args", "text").optional(), Param.of("vm", "text").optional(), Param.of("directory", "text").optional(),
                        Param.of("environment", "text").optional()), args -> workspace.runConfig(new RunConfigs.Config(text(args, "name").strip(),
                        text(args, "type"), optional(args, "args").orElse(""), optional(args, "vm").orElse(""),
                        optional(args, "directory").orElse(""), optional(args, "environment").orElse(""))).shown()))
                .register(Capability.of("run-config-remove", "Removes a run configuration", List.of(Param.of("name", "text")), args -> {
                    workspace.runs().remove(text(args, "name"));
                    return workspace.runs().shown();
                }))
                .register(Capability.of("library", "Refactorings by level", List.of(), args -> workspace.library()))
                .register(Capability.of("define", "Registers refactorings written in .ref", List.of(Param.of("text", "text")),
                        args -> workspace.define(text(args, "text"))))
                .register(Capability.of("diagnostics", "Compiler errors and warnings", List.of(), args -> workspace.diagnostics().stream()
                        .map(d -> entries("code", d.code(), "path", d.path(), "line", d.line(), "message", d.message(),
                                "severity", d.severity().name(), "column", d.column(), "length", d.length())).toList()))
                .register(Capability.of("check", "The problems if a file said what an editor says, without applying it",
                        List.of(path, Param.of("text", "text")), args -> workspace.check(text(args, "path"), text(args, "text")).stream()
                        .map(d -> entries("code", d.code(), "path", d.path(), "line", d.line(), "message", d.message(),
                                "severity", d.severity().name(), "column", d.column(), "length", d.length())).toList()))
                .register(Capability.of("fixes", "Quick fixes for a line", List.of(path, line),
                        args -> workspace.fixes(text(args, "path"), number(args, "line"))))
                .register(Capability.of("fix", "Applies a quick fix, with the answers to what it asks",
                        List.of(path, line, Param.of("title", "text"), Param.of("answers", "map").optional()),
                        args -> Session.report(workspace.fix(text(args, "path"), number(args, "line"), text(args, "title"),
                                answers(args)))))
                .register(Capability.of("asks", "What a quick fix asks, and what it answers if not told",
                        List.of(path, line, Param.of("title", "text")), args -> workspace.questions(text(args, "path"),
                                number(args, "line"), text(args, "title")).stream()
                                .map(ask -> entries("name", ask.name(), "question", ask.question(), "default", ask.defaultAnswer()))
                                .toList()))
                .register(Capability.of("edit", "Replaces a file's text with what was written by hand",
                        List.of(path, Param.of("text", "text")),
                        args -> Session.report(workspace.edit(text(args, "path"), text(args, "text")))))
                .register(Capability.of("at", "The element whose name is at a line and column of a file",
                        List.of(path, line, Param.of("column", "text")),
                        args -> workspace.at(text(args, "path"), number(args, "line"), number(args, "column"))
                                .map(e -> entries("element", e.qualified(), "kind", e.kind().name())).orElse(null)))
                .register(Capability.of("where", "Where an element is declared: file, line and column",
                        List.of(Param.of("element", "element")), args -> workspace.where(text(args, "element"))
                                .map(at -> entries("path", at.path(), "line", at.line(), "column", at.column())).orElse(null)))
                .register(Capability.of("elements", "The workspace's elements of some kinds (comma-separated), or all; in a file, if given",
                        List.of(Param.of("kinds", "text").optional(), Param.of("path", "text").optional()),
                        args -> workspace.elements(args.containsKey("kinds") ? List.of(text(args, "kinds").split(",")) : List.of(),
                                Optional.ofNullable(args.get("path")).map(String::valueOf)).stream()
                                .map(e -> entries("element", e.qualified(), "kind", e.kind().name())).toList()))
                .register(Capability.of("why", "How the fixes for a line are deduced", List.of(path, line),
                        args -> workspace.why(text(args, "path"), number(args, "line")).lines()))
                .register(Capability.of("complete", "Completions at an offset of edited text",
                        List.of(path, Param.of("text", "text"), Param.of("offset", "text")),
                        args -> workspace.completions(text(args, "path"), text(args, "text"), number(args, "offset")).stream()
                                .map(c -> entries("label", c.label(), "kind", c.kind())).toList()))
                .register(Capability.of("preview", "A refactoring's diff, without keeping it", List.of(refactoring, itsArgs),
                        args -> {
                            var preview = workspace.preview(text(args, "refactoring"), nested(args));
                            var answer = Session.report(preview.result());
                            answer.put("diff", preview.diff());
                            return answer;
                        }))
                .register(Capability.of("inside", "The innermost member, parameter or type a line and column of a file is inside",
                        List.of(path, line, Param.of("column", "text")),
                        args -> workspace.inside(text(args, "path"), number(args, "line"), number(args, "column"))
                                .map(e -> entries("element", e.qualified(), "kind", e.kind().name())).orElse(null)))
                .register(Capability.of("around", "What a place of a file is inside, innermost first: line, column, end line, end column",
                        List.of(path, line, Param.of("column", "text")), args -> workspace.around(text(args, "path"), number(args, "line"),
                                number(args, "column")).stream().map(s -> entries("line", s.line(), "column", s.column(),
                                "endLine", s.endLine(), "endColumn", s.endColumn())).toList()))
                .register(Capability.of("options", "The elements a refactoring's parameter may take, given the others; nothing if any of its kinds will do",
                        List.of(refactoring, Param.of("param", "text"), itsArgs),
                        args -> workspace.options(text(args, "refactoring"), text(args, "param"), nested(args)).orElse(null)))
                .register(Capability.of("suggest", "What a refactoring suggests for the parameters not given, from those given",
                        List.of(refactoring, itsArgs), args -> workspace.suggest(text(args, "refactoring"), nested(args))))
                .register(Capability.of("expand", "A refactoring's primitives, without applying them",
                        List.of(refactoring, itsArgs), args -> workspace.expand(text(args, "refactoring"), nested(args))))
                .register(Capability.of("why-repair", "How the last transaction's repairs were searched", List.of(),
                        args -> workspace.whyRepair().lines()))
                .register(Capability.of("callers", "Who calls a method (polymorphically)",
                        List.of(Param.of("method", "element", "METHOD")), args -> workspace.callers(text(args, "method"))))
                .register(Capability.of("hierarchy", "Supertypes and subtypes of a type",
                        List.of(Param.of("type", "element", "CLASS", "INTERFACE", "ENUM")), args -> workspace.hierarchy(text(args, "type"))))
                .register(Capability.of("references", "Every use of an element",
                        List.of(Param.of("element", "element")), args -> workspace.references(text(args, "element"))));
    }

    private static String guide() {
        try (var in = Capabilities.class.getResourceAsStream("guide.md")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String text(Map<String, ?> args, String name) {
        var value = args.get(name);
        if (value == null) throw new IllegalArgumentException("missing argument: " + name);
        return String.valueOf(value);
    }

    /** Who does it: the window says the person; an agent need not say. */
    private static Param by() {
        return Param.of("by", "choice", "agent", "person").optional();
    }

    /** Who does it: the person when the window says so, else the agent. */
    private static String who(Map<String, ?> args) {
        return "person".equals(args.get("by")) ? "person" : "agent";
    }

    private static Optional<String> optional(Map<String, ?> args, String name) {
        return Optional.ofNullable(args.get(name)).map(String::valueOf).filter(value -> !value.isBlank());
    }

    private static int number(Map<String, ?> args, String name) {
        return Integer.parseInt(text(args, name));
    }

    /** An answer's fields, in the order given: names and values, alternating. */
    private static Map<String, Object> entries(Object... namesAndValues) {
        var entries = new java.util.LinkedHashMap<String, Object>();
        for (var i = 0; i < namesAndValues.length; i += 2) entries.put((String) namesAndValues[i], namesAndValues[i + 1]);
        return entries;
    }

    /** The answers to what a quick fix asks, by name. */
    private static Map<String, String> answers(Map<String, ?> args) {
        var answers = new java.util.LinkedHashMap<String, String>();
        if (args.get("answers") instanceof Map<?, ?> given) given.forEach((k, v) -> answers.put(String.valueOf(k), String.valueOf(v)));
        return answers;
    }

    /** The arguments of the refactoring a capability is about. */
    @SuppressWarnings("unchecked")
    private static Map<String, ?> nested(Map<String, ?> args) {
        return args.get("args") instanceof Map<?, ?> nested ? (Map<String, ?>) nested : Map.of();
    }
}
