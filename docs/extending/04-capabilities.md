# Adding a capability

A capability is **a schema plus an invocation**: a name, a description, typed parameters, and a function from
arguments to a JSON-able answer. Every operation of the IDE is one: refactorings, quick fixes, navigation, the
plans, running programs. The adapters only translate:

- the **CLI** binds words to the schema: `ide> rename a.P#x y`, or `name=value`;
- the **MCP server** lists it as a tool with a JSON Schema, so agents can call it;
- the **window** calls it, through forms generated from the same schema when it asks for arguments.

Every refactoring in the library, including those you `define`, is already a capability. Add a capability by hand
only for operations that are not refactorings: queries, views, state that tools share.

## Where the code goes

1. **The domain**, in `session`: a class in its own vocabulary (see `Attention`, `RunConfigs`, `Plans`), built by
   `Workspace`, which only delegates to it.
2. **The registration**, in `session/src/main/java/ide/session/Capabilities.java`:

```java
.register(Capability.of("point", "Shows the person a place in the IDE's window, with a note: an element (where it "
        + "is declared), or lines of a file, line to end", List.of(Param.of("element", "element").optional(),
        Param.of("path", "text").optional(), Param.of("line", "text").optional(), Param.of("end", "text").optional(),
        Param.of("note", "text").optional()), args -> {
    var from = args.containsKey("line") ? number(args, "line") : 0;
    return workspace.point(optional(args, "element"), optional(args, "path"), from,
            args.containsKey("end") ? number(args, "end") : from, optional(args, "note").orElse("")).shown();
}))
```

Parameter types include `text`, `element`, `elements`, `bool`, `choice` (with its options), `map` and `facts`; mark
them `.optional()` when they are. The description is what an agent reads to decide when to call it, so write it
for that reader.

3. **The answer** is maps, lists, strings and numbers: what `Json` writes. A record in the domain usually has a
   `shown()` that turns it into a map.

## What must not happen

- **No logic in an adapter.** If the window needs to decide something about the program, such as which classes have a
  `main` or what an element is under the caret, that decision is a capability (`mains`, `looking`). The window only
  calls it and shows the answer. `AdapterTest` in each adapter enforces what they may depend on.
- **No Java in `session` beyond the driver's facade.** Language knowledge stays in the `java` driver, which is reached
  through `JavaProjection`.
- **State is an event.** If the capability changes something, record it in the event log (`events.jsonl`), as
  `point` and `run-config-set` do.

## Testing

A session test invokes the capability as an adapter would, through the registry:

```java
@Test
void pointingAtAnElementIsPointingAtItsWholeDeclaration() throws Exception {
    var ws = open();

    var pointed = call(ws, "point", Map.of("element", "a.P#get()", "note", "this one reads x"));

    assertThat(pointed).containsEntry("path", "a/P.java").containsEntry("line", 6L).containsEntry("endLine", 8L);
}
```

`call` here is `Json.read(ws.capabilities().invoke(name, args))`, so the test sees exactly what an agent would.

## Making agents use it well

The MCP server sends agents a guide as its instructions, from `session/src/main/resources/ide/session/guide.md`.
If your capability changes how an agent should work, such as a new way to show the person something or to report
progress, mention it there in backticks. `GuideTest` checks that every name the guide mentions is a real
capability.

## Showing it in the window

If people should reach it from the window, add the action in `ui` (`Workbench`). Give it a menu entry and a key in
`Keymap`, as IntelliJ or Eclipse would. The action calls the capability with `ide.call(name, args)` and shows the
answer. The UI is tested without a screen (`WorkbenchTest`), with dialogs scripted through `ScriptedDialogs`.
