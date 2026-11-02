# IdeSeed

A small Java IDE built on one idea: **the program is a graph with stable identity, and the text is a projection of
it.** Every structural change is a composition of a handful of primitives, and correctness does not come from how
carefully each refactoring was written but from *verifying* the result against what the code meant before, then
repairing it or refusing it.

It is meant to be used by a person and by an AI agent at the same time, on the same project, through the same
operations.

## Why

In most IDEs and in most AI coding tools, a change to a program is a change to text. A rename is a search and
replace that hopes it found every use; an agent's edit is a diff that hopes it still compiles and still does the
same. When it goes wrong, someone notices later, or never.

IdeSeed works the other way around:

- **Identity, not text.** Types, members, parameters, locals and the statements of a body are elements of a graph,
  each with an id that survives renames, moves and edits by hand. Uses are references bound to those ids. A file is
  just how the graph is written in Java right now.
- **Few primitives.** Create, Delete, Move, Rename, Set, Link, Retarget, Extract, Inline. Every refactoring is made of
  them; a new primitive is added only when it cannot be composed.
- **Verified, not trusted.** Each operation runs in a transaction. After the change the code is resolved again and
  compared with the baseline through several views: what every use binds to, access, dispatch (which method a call
  really reaches), and the compiler's diagnostics. If something changed meaning, the engine repairs it from what it
  knows or rolls everything back and says why. No check is ever switched off silently.
- **Minimal, lossless edits.** Formatting and comments are kept; diffs are as small as the change.
- **Knowledge is declarative.** How Java works (access, construction, overriding, exceptions, final fields...) is
  written as rules (Datalog), not as code. Quick fixes and repairs are *deduced* from those rules by a planner,
  never hand-written per case.
- **Intents.** A refactoring can say only what must hold at the end (`want`), and the planner finds the changes,
  plus whatever else has to change for the code to still be Java and still do the same.

## What it can do

- **Refactorings**, from the Java driver's primitives (rename, move, extract method or expression, inline variable or
  method, change signature...) and from a catalog of scripts in a small language (`.ref`), such as encapsulate field,
  pull up / push down members, extract interface, extract superclass, introduce parameter object, introduce
  factory, safe delete, collapse hierarchy, merge duplicate, convert to instance method, make static, split loop.
  Catalog scripts write no Java: statements are said in a language-neutral body language, and the driver writes
  them.
- **Quick fixes** for compiler errors and rule violations, planned from the knowledge base (at most a few effects,
  cheapest first), each verified before it is offered as done.
- **Edits by hand** are reconciled: when you type, the IDE works out which elements your edit kept, moved or removed,
  so identity survives. Changes made on disk by other tools are picked up the same way.
- **A desktop IDE** (Swing, IntelliJ-like look and keys): editors with diagnostics, completion, intention bulbs,
  navigation, a project tree, a terminal, and running classes with a `main` (green gutter arrows, run
  configurations, a console with stdout/stderr).
- **The same capabilities everywhere.** Every operation is a *capability*: a JSON schema plus an invocation. The
  CLI, the UI and the MCP server are thin adapters with no logic of their own, so whatever the window can do, a
  script or an agent can do too.

## How it helps with AI

IdeSeed is designed so that an agent such as Claude Code can work *with* the IDE instead of around it.

- **An MCP server for every capability.** `ide-mcp.jar` serves the project over stdio, and the desktop IDE serves its
  own open project over HTTP (localhost only, with a bearer token). Every refactoring, quick fix, query and
  navigation is a tool with a JSON Schema. The server tells the agent how to work with the IDE in its instructions.
- **Safe changes for agents.** An agent asking for `rename` or `move-members` gets a verified transaction, not a text
  diff: if the result would change what the code does, it is repaired or refused with a reason. The agent can
  prefer refactorings over editing text, and an edit by text is still reconciled and checked.
- **Shared attention.** The agent can *point* the person at an element or lines, with a note, and the window opens
  and selects it. It can ask *where the person is looking*: the file, the caret, the selection, and what element
  that is in the program.
- **Plans you can see and steer.** The agent keeps its work as plans: trees of tasks with descriptions, dependencies,
  links into the code and a timeline. The IDE shows them beside the editors: progress, each task's last step, its
  details, and the part of the conversation that happened while it was being done. From there the person can
  start, pause, stop, comment on or replan a task, or switch plans; that is typed into Claude's terminal when it is
  running there, or delivered with the IDE's next answer.
- **Progress at no token cost.** Claude Code is started from the IDE (Ctrl+Esc) with a hook that reports each tool it
  uses, so every task shows its micro-steps without the agent having to report them.
- **Continuity.** The IDE resumes the agent's last conversation on the project, and if Claude was working when the IDE
  closed, it goes on by itself when the project is opened again.

## Getting started

Requirements: JDK 25 (the code targets Java 21) and Maven.

```sh
mvn test                                   # build every module and run every suite
mvn package -DskipTests -pl cli,ui,mcp -am # build the runnable adapters

java -jar ui/target/ide-ui.jar [folder]    # the desktop IDE (no folder: the last one opened, else an example)
java -jar cli/target/ide-cli.jar <folder> [capability args...]   # one command, or a shell with none
java -jar mcp/target/ide-mcp.jar <folder>  # an MCP server on stdio
```

The folder is a Java source root or a Maven project; libraries come from what Maven says the project uses. The IDE
keeps its own state (event log, plans, run configurations, caches) in an `.ide` folder inside the project.

To use it from Claude Code without the window, register the stdio server:

```sh
claude mcp add ideseed -- java -jar /path/to/mcp/target/ide-mcp.jar /path/to/project
```

Inside the desktop IDE, Ctrl+Esc opens Claude Code in the terminal already connected to the IDE.

## How it is built

One Maven module per product; each is designed as if it were an independent library with its own domain, and the
poms are the dependency map:

| Module | What it is |
|---|---|
| `core` | Capabilities, JSON, the append-only event log, text diffs |
| `program` | The program graph: elements, references, addresses, identity, primitives, the body language |
| `projection` | The contract a language driver fulfils; `projection-kit` has its tests and an in-memory driver |
| `java` | The Java driver (JavaParser for reading, Eclipse's compiler for diagnostics), in layers |
| `engine` | Transactions, the verifier and its views, the repairer |
| `refactoring` | Refactorings, the `.ref` script language and the standard catalog |
| `rules` | A Datalog engine, knowing nothing of programs |
| `knowledge` | The goal-driven planner over the Java rules: quick fixes, repairs, intents |
| `maven` | What Maven says about a project (its classpath) |
| `session` | The workspace: undo, saving, sync with disk, plans, attention, conversations, run configurations |
| `cli`, `ui`, `mcp` | Adapters, with no logic |
| `acceptance` | Scenarios across every product, over realistic samples |

The design reference, with a sheet per module, the decisions taken and the plan, is
[ARCHITECTURE.md](ARCHITECTURE.md) (in Spanish). [CLAUDE.md](CLAUDE.md) is a map of the code as it is today.

## How it is developed

Strict TDD: a failing acceptance test that runs a real refactoring or quick fix over inline sources, the minimal
change to make it pass, then the model improved while green, one commit per cycle. Most of the code was written by
AI agents (Claude Code) working in that loop, with architectural direction and review from a person.
