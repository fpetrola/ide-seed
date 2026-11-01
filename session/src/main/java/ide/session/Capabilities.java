package ide.session;

import ide.core.Capability;
import ide.core.CapabilityRegistry;
import ide.core.Param;
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
                .register(Capability.of("library", "Refactorings by level", List.of(), args -> workspace.library()))
                .register(Capability.of("define", "Registers refactorings written in .ref", List.of(Param.of("text", "text")),
                        args -> workspace.define(text(args, "text"))))
                .register(Capability.of("diagnostics", "Compiler errors", List.of(), args -> workspace.diagnostics().stream()
                        .map(d -> Map.of("code", d.code(), "path", d.path(), "line", d.line(), "message", d.message())).toList()))
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
                                Optional.ofNullable(args.get("path")).map(String::valueOf))))
                .register(Capability.of("why", "How the fixes for a line are deduced", List.of(path, line),
                        args -> workspace.why(text(args, "path"), number(args, "line")).lines()))
                .register(Capability.of("complete", "Completions at an offset of edited text",
                        List.of(path, Param.of("text", "text"), Param.of("offset", "text")),
                        args -> workspace.complete(text(args, "path"), text(args, "text"), number(args, "offset"))))
                .register(Capability.of("preview", "A refactoring's diff, without keeping it", List.of(refactoring, itsArgs),
                        args -> {
                            var preview = workspace.preview(text(args, "refactoring"), nested(args));
                            var answer = Session.report(preview.result());
                            answer.put("diff", preview.diff());
                            return answer;
                        }))
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

    private static String text(Map<String, ?> args, String name) {
        var value = args.get(name);
        if (value == null) throw new IllegalArgumentException("missing argument: " + name);
        return String.valueOf(value);
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
