# IdeSeed

Minimal Java IDE where the program is a graph with stable identity and the text is a projection.
Every structural change is made of a few primitives; correctness comes from verification, not care.

## Build and test

- `mvn test` from the root builds every module and runs every suite (JUnit 5, AssertJ, ArchUnit).
  Java 21 (`maven.compiler.release`), runs on JDK 25.
- `mvn test -Dtest=OrdersSampleTest` runs one class wherever it lives; `mvn test -pl engine -am` runs one product's
  suite (with the products below it built first). After moving code between modules, use `mvn clean test`: stale
  classes and resources in `target/` have hidden broken code here before.
- Tests tagged `probe` (performance) are excluded by default; run them with
  `mvn test -Dtest=PerformanceProbe -DexcludedGroups=none`.
- `mvn package -DskipTests -pl cli,ui,mcp -am` builds the adapters as runnable jars: `java -jar cli/target/ide-cli.jar
  <folder> [capability args...]` (no capability: a shell), `java -jar ui/target/ide-ui.jar [folder]`, and
  `java -jar mcp/target/ide-mcp.jar <folder>` (an MCP server on stdio). The folder is a source root.

## Map

One Maven module per product; each pom declares only the products it uses, so the poms are the dependency map and
the compiler enforces it. Folder `<product>/`, artifact `ide-<product>`, package `ide.<product>`.

- `core` — Capability, CapabilityRegistry, Param, Json (written and read), EventLog (JSONL), TextDiff (also what two
  sequences share in order). Knows nothing else.
- `program` (core) — the graph: Element/ElementId/Kind (what a body is made of, `inBody`: LOCAL and STATEMENT, the
  statements of a body, in order, and those of the blocks a statement holds, its own, in order: `m()[2][1]`;
  a lambda's or a class's are theirs), Fact (typed facts; LIBRARY marks a library type the
  workspace names: never changed, its name is its id, out of the workspace hierarchy; INITIALIZED a field given its
  value where it is declared; THROWS what a callable declares it throws, as written; ANNOTATIONS what a declaration is annotated with, as
  written, which `set` replaces), Unresolved (a use that binds
  nothing, same id space as references, with the type a member is looked up in; a Retarget binds it),
  Reconciliation (after an edit by hand, pairs what changed address with what it was, only when the structure leaves
  no doubt), Reference (id `site@ordinal`; site is the member that makes the use, `statement` the innermost
  statement of the body it is in, if any; `referencesIn(statement)`, those nested in it too; Mode:
  uses read|write|update|call|new|type and links extends|implements|declared; Receiver: what a use is made on, the
  object itself, a type or another object, none for links, types and news), Program (indexed queries, diff,
  `expecting(Change)`, `outline()`), Address (typed: types by qualified name, members by owner id, callables by
  parameter type keys, locals by their callable's id, name and which of that name they are: `m()/i'2`, statements by
  their callable's id and place: `m()[1]`) and Identity (address → id; rebindType, rename, move, placeLocals: a
  callable's locals in written order, each numbered among those of its name, all at once; placeStatements, the same
  for statements, with free places for new ones; resign, forget). `Program.memberOf` is the method a statement or a
  local is part of. Reconciliation also pairs a body's statements after an edit by hand by their order and text. The vocabulary of
  change: Primitive (sealed records Create, Delete, Move, Rename, Set, Link, Retarget, Extract, Inline; each states
  its `intent`; a Delete `into` another member merges it: expectation Merged, which takes the element out of the
  baseline; an Extract's fragment names statements by their ids, or is what only the projection understands; a
  statement is created said in the body language at the end of a body, moved before another or into another method
  with the uses made in it, and deleted with them; one that calls a constructor goes first), Change (produced element,
  expectations, pins: what a repair may not take back) and Body (what a method does in no language, a statement a
  line: return, assign a field, new, a call, the type's default, a constructor called first, a new list, add to a
  list, each value of a list in a local (a loop made empty, which declares it; `list of #{T}` is also a local's
  type), over elements named `#{id}`; a statement is created at the end of a body or of the one block a statement of
  it holds; a parameter or local it names is a read of it; a created statement expects the uses it names Added; a body is its
  statements: there is no Set of a whole body).
- `projection` (program) — the language driver contract: Projection (`program`, `apply(Primitive)` → Change, `edit` (a null text: the file gone),
  typed `snapshot`/`restore`, `rules`, `diagnostics`, `completionContext`, `locate` and `elementAt`: where an element
  is declared and what the name at a line and column means, `needs`: what a use that binds nothing needs, asked for
  one use, `said`: a statement, or a whole body, in no language when all of it can be said so), LanguageRules (access, visibilities, how
  the language writes a field's default value), Diagnostic,
  CompletionContext, PrimitiveRejected (and the rules every driver applies: nothing changes a library element; a
  field merges only into a field, a method into a method, a local into a field of its name, a parameter into its method's type, of that type: the object itself).
- `projection-kit` (projection, JUnit, AssertJ) — the kit for driver writers: ProjectionContract (the obligations as
  tests over a fixture each driver writes in its language) and MemoryProjection (a projection with no language,
  with a Builder and the rules OPEN and PRIVATE_TO_TOP_LEVEL; the stand-in for a driver in the tests of engine,
  refactoring and knowledge).
- `java` (projection, JavaParser) — the Java driver, in layers that only know the ones below (LayersTest):
  `ide.java.text` (Unit, EditSet, Sources: files change only as text, all or nothing; the only place that parses),
  `ide.java.syntax` (Decl, Members, Modifiers, Lists, Imports, Render: Java constructs written as text),
  `ide.java.semantics` (Reader: program + declarations + use nodes; Resolution, Keys, WorkspaceTypeSolver,
  EcjCheck: Eclipse's compiler, incremental, errors and warnings with their range; Bodies, Completion), `ide.java.primitives` (Context and one class per primitive; WriteBody writes a
  statement in Java: names, a field qualified where a parameter hides it, a new object's type imported), and the facade
  `ide.java.JavaProjection` (sources, identity, reading, invalidation). The reader adds the library types the code
  names and keeps written types that bind nothing as unresolved uses, with the JDK types they may mean (JdkTypes);
  binding one writes the name and imports it. A field or call that surely binds nothing (for a call: no method of that
  name where it is looked up) is an unresolved use too; binding it renames the use. What one needs (expected type,
  arguments) is asked of the projection (semantics.UseNeeds). The locals of a method or constructor (not of a lambda or
  a class inside it) are its elements and their uses references from it, and so are the uses of its parameters (on
  nothing, in their statement); JavaRules reaches a local only from inside
  its method. An expression extracted may be operands of a String concatenation next
  to each other, from inside a literal: it is split and they are grouped, when the value stays the same. Extract moves the locals declared only in its fragment, identity and all, and says which uses of the
  others are gone (they are the new method's parameters, or its own local). A renamed local may take the name of one
  in a block beside it (its method's locals are numbered anew); a capture is the verifier's binding.rebound. Inline
  replaces a read of a local by its value (InlineLocal) and refuses what would change behavior without changing a
  binding (another value later, effects unless read once with nothing between that could tell, when the value
  moves there, another type, what it reads changed between, a read in a lambda); a local
  nothing reads is deleted with its lines; a deleted parameter takes its argument from every call, whose uses go on
  purpose (one with effects is refused). The statements of each body are elements, and so are those of the blocks
  each holds (Bodies.nested); a statement moves before one of a nested block, alone on its lines, or to the end of
  the one block a statement holds, the same way; the
  primitives that change a body's list keep their ids (extract takes its own along), and an edit by hand pairs them by order and text.
  `inside` answers the innermost element, a statement or a local too. `mains` are the classes a program runs from: the first
  top-level class of a file with a main Java launches (static with `String[]`, or Java 25's instance or no-argument). An edit by hand reconciles identity (types, members,
  parameters, reading again between rounds) and forgets what it removed. A new parameter's argument may be said in no language over the method's parameters (each call passes
  what it passed them); a read of a parameter or local retargeted to a getter is made on the one variable of the
  getter's type there, and so is a call on the object itself retargeted to a method of another type; a call
  retargeted to a local is computed where the local is given its value: one on a branch, in a loop
  or a nested block the local is declared outside of is refused. Inline of a call made as a statement (or as a lambda's body) to a method of statements
  puts them in its place, each parameter its argument (a return among them is refused; a local of theirs named as one of the caller's, or of a lambda the call is in,
  takes a free name, `name2`); a method reference (through its type, static, or through `this`) is first the lambda that
  calls it. A member moved to another file takes the imports of the simple names it uses, and a static one is used
  from elsewhere through its new type; it names the static members of its old type through it, and in its new one what
  was named through that type is named as its own (so is a field made one each object has). A new type's file, created or moved, goes in a source root (a file's folder without its
  package's) with files of its package, else in the one with most files. Nested types are deleted like members; a member merged
  into another has its uses renamed in the same edit that removes it. A reading after a change re-reads the files changed and
  re-resolves only the uses that can bind differently (every file when a type changed; else by member name, through
  the changed member's type, supertypes and subtypes); `resolvedLastTime()` tells which. The AST is never edited.
  Other modules use
  only the facade (ArchitectureTest). Runs ProjectionContract in its tests.
- `engine` (projection) — Transaction (born with its Repairer; intercepts `apply`, folds each declared Change into
  the baseline, pins; `attempt` undoes what is refused or does not verify; a point restores the baseline and pins
  too; `commit()` verifies and repairs),
  Verifier (a list of Views compared through a Comparison: BindingView, which also sees a written body binding
  something else than it names, and takes a call now binding what its old target still overrides as the same
  dispatch, AccessView, DispatchView, which ignores an override no call of the overridden method
  can reach, and CompilerView: every error that was not there before, even if the code did not compile; costly,
  last), Repairer (plans from saved points), Trace (the tree of what ran or was searched; `lines()` is the
  only place that writes sigils), Result, Plan/Remedy/QuickFix. Tested alone over MemoryProjection.
- `refactoring` (projection, engine) — Refactoring (`apply(Run, Args)` answers an ElementId), Library, Run (one
  kind, two places: `Run.in(tx, library)` inside a transaction; `Run.on(projection, library)` with no
  engine, which is how scripts are tested), Args (the one place an id-or-address is resolved), Value (Elem, Ref,
  Text, Bool, Items), Primitives (read off the Primitive records: nothing registered by hand), ChangeSignature (the
  only Java composite); the `.ref` language in `ide.refactoring.script` (in a text, `${expr}` writes a value and
  `#{expr}` writes elements as a body names them; an `intent` is params and one `want`, what is to hold at the end,
  kept as text and made to hold by the library's Planner through `Run.want`, refused with none; a refactoring that
  says `changes meaning` may change when things run, which no verifier can tell: its capability says so first, for a
  person to choose it knowing it); the catalog
  `refactoring/src/main/resources/ide/refactoring/standard.ref`, which writes no code of any language.
- `rules` (nothing) — Datalog as a tool apart: Term, Atom, Literal, Clause, Rules (built from clauses or from text
  written as any Datalog reads it; refused if unsafe or not stratified), Tuple, Facts (relations asked by pattern: a
  view, never a copy), Engine (`standard()`: on demand, from the query down; a recursive relation to its fixpoint) and
  Evaluation (answers, holds, `suppose`: the same rules with facts added and removed). `RulesContract` is what every
  engine answers alike. Knows nothing of programs.
- `knowledge` (engine, refactoring, rules) — goals/facts/effects planner over
  `knowledge/src/main/resources/ide/knowledge/knowledge.ref` (`Knowledge.standard(library)`); it searches a
  violation or a diagnostic with the facts each gives, backward: what an effect `needs` holds by a `fact` or is made
  first by another effect (plans of at most 4 effects, the 12 cheapest); effects run on the real Run, straight on the
  projection; no remedy or quick fix is written in Java. How Java is lives in `java.rules`: definitions (`def`, Datalog
  evaluated by `rules`; `order` gives ordered values, `say` how a change is said). A predicate that definitions say is
  made true by abduction (`Abduction`): the body read left to right, the graph (`GraphFacts`, a view of Program in no
  language; a state supposed is the graph as it would be: what is derived from where an element is follows where it
  is supposed to be) binding variables, and a fact with known arguments that does not hold changed by the primitive
  that changes it (visibility, parent, place, what a use binds, a constructor nobody has). Every effect is a quick fix; only one that says `keeps meaning`
  may also repair a refactoring by itself (a Plan that `changesMeaning` is never taken by the Repairer). It is also
  the library's Planner (`Knowledge.standard` registers it): a want is read as a definition's body and made to hold
  the same way, what that breaks mended in the same plan (rule by rule, as written) without what the want asks for
  counting toward the 4; the cheapest plan that keeps meaning runs on the Run, with no mended one the want alone, and
  the repairer after.
- `maven` (nothing) — what Maven says about a project: `Maven.project(folder)` is the nearest folder with a pom.xml,
  and `classpath(cache)` is what `dependency:build-classpath` answers (test scope, every module of a reactor),
  remembered in a file while the POMs' sizes and times do not change; what Maven cannot resolve is said, not thrown.
  Its tests run Maven (dependencies already in `~/.m2`).
- `session` (java, engine, refactoring, knowledge, maven; JavaParser only at run time) — parts over a `JavaProjection`:
  Session (each operation a transaction; undo, event log, saving; a fix must make its diagnostic disappear), Fixes
  (the quick fixes of a line; a Fix is a diagnostic and its plan), Navigation and Completion (no language: tested
  over MemoryProjection), Capabilities (each refactoring and operation with its schema, including what the adapters
  asked for: save, edit, asks, at, where, elements, uses (each use by the id a retarget takes); `inside` is the member a place is in, as scripts' inside is).
  `Workspace` builds the parts and only delegates;
  `Workspace.open(folder)` reads a project (skipping target, build, out and hidden folders), gives the Java driver
  the classpath Maven says it has (cached in `.ide/classpath.txt`), logs to `.ide/events.jsonl` there and saves there;
  `sync` takes what changed there on disk since it was read or saved, each file as an edit by hand (a file the IDE
  changed too is a conflict left alone; one deleted there is an edit by hand that takes it and what it declared out,
  unless the IDE changed it: a conflict). Plans (not the engine's repair Plan): the plans a
  person and an agent follow, several, one active; each a tree of tasks (lines of action and subtasks, depth first)
  with stable ids (`p1`, `t7`), a description, what each waits for, links into the code and a timeline of who said or
  did what; in `.ide/plans.json`, each change an event; capabilities plans, plan, plan-new, plan-switch, plan-state,
  task, task-add, task-update, task-link. What the person says (`steer`: start, pause, resume, stop, comment, replan, switch)
  changes the plan at once and waits to be told to the agent once (`steering`), unless the window told it. What the agent does
  with its tools (`activity`, an event as Claude Code's hooks give it) is a step of the task it started last and still
  does, said in a few words (Activity); the plan shows each task's last step. Conversation: the agent's conversation on the
  project (the session id its hooks tell), and whether it was open when the IDE closed, in `.ide/conversation.json`;
  `mains`, `run-command`: how to run a
  class's program as saved (the IDE's own Java launches it from its sources, with Maven's classpath, in the project's
  folder), by a run configuration (RunConfigs, in `.ide/runs.json`: name, class, arguments and VM options split as a
  shell does, folder, environment `NAME=value;...`; one chosen; a class run with none gets one named after it);
  run-configs, run-config-set, run-config-remove. capabilities conversation, conversation-open, conversation-in (and where it is written). Transcript reads the
  conversation as Claude Code writes it (JSONL), only what was added since and only the lines that can matter: what the
  person said, what the agent answered, the tools it used; `task-talk` is what of it was said while a task was being
  done (Plans.doing: of the tasks being done then, the one started last). Attention: where attention is, both ways, between a person and an agent on the same
  IDE: `point` (an element or lines, with a note; numbered, an event) and `pointed`; `look` (the window says where the
  person is) and `looking` (that, with the element under the caret and what it is inside); pointing at an element marks its whole
  declaration (`JavaProjection.declared`). `guide` (resource `guide.md`): how an agent works on the IDE, what MCP says
  as its instructions when an agent arrives (GuideTest: it names only capabilities that exist).
- `acceptance` (session, tests only) — not a product: scenarios across every product (refactorings, quick fixes,
  completion over Java text), the samples in `acceptance/src/test/resources/samples` (case tables in
  `OrdersSampleTest` and `ShopSampleTest`), and the two ArchUnit rules Maven cannot state.
- Adapters (session, core; each with an `AdapterTest` that keeps it from anything else): they talk to the IDE only
  through capabilities, in JSON, and decide nothing. `cli` (Command binds words to a capability's schema, Text writes
  JSON as text, Cli: one command, a script, or a shell), `ui` (Swing with RSyntaxTextArea, IntelliJ's menus, both IDEs' keys; with no folder it
  opens the last one it opened, kept in `~/.ideseed/last`: Workbench, Editors, Views, Form from a capability's schema, Dialogs as an interface the tests script, Keymap, Terminal:
  JediTerm over pty4j, the user's shell in the project's folder, started when first shown, Alt+F12; tested with no
  screen; the one adapter that uses another: the window serves its own IDE over mcp's Http, each call on Swing's
  thread, after a sync, and saved right after, so an agent's own file tools and the IDE never disagree; Watcher syncs
  when the folder changes; Ctrl+Esc runs Claude Code in the terminal with that server; PlansView, beside the
  editors, shows the plans: which one, how far it got, its lines of action as a tree, and the chosen task with its
  links (they open the code) and timeline; what the person says there (start, pause, stop, comment, replan) is steered, typed
  into Claude's terminal if Claude runs there (stop presses Esc first), else told with the next answer; Claude Code
  is started with a hook (`--settings`) that tells `activity` each tool it used, so each task shows its steps at no
  cost to Claude; Ctrl+Esc goes on with Claude's conversation on the project (`--resume`: the one its hooks told,
  else the latest Claude Code keeps of the folder), Ctrl+Shift+Esc starts a new one, and a project opened again goes
  on by itself if Claude was working when the IDE closed; closing the terminal ends what runs in it, not only its shell; a class with a main shows IntelliJ's green arrows (its line and its main's): a click, Ctrl+Shift+F10,
  runs it saved first, in the Run view (RunConsole: stdout, stderr in red, input, rerun Ctrl+F5, stop Ctrl+F2), by its
  run configuration; the toolbar's list (top right) chooses one, Shift+F10 runs it, Alt+Shift+F10 edits them (a form
  from run-config-set's schema); a task's details have a Conversation tab: what was said while it was
  being done, tools used one after another as one line; before each call it says where the person looks, after it
  opens and selects what an agent pointed at), `mcp` (Server: JSON-RPC 2.0, each capability
  a tool with a JSON Schema (a call that fails, even by a StackOverflowError or a LinkageError, an error result), list_changed after a call that changes the tools, the guide as its instructions; `handle` is the protocol, `run` serves it
  on stdio and Http on Streamable HTTP, 127.0.0.1 only, with a bearer token).

## Principles (do not break them; say so if an instruction does)

1. The core knows no Java: language semantics live in the `java` driver and in knowledge (the poms enforce it).
2. A capability is schema + invocation; adapters (CLI, UI, MCP) have no logic.
3. Minimal primitives, robust engine: a new primitive only if it cannot be composed.
4. Minimal, lossless edits: keep formatting and comments, small diffs.
5. Correctness by verification: transform naively, re-resolve, compare, repair or abort; declare what cannot be
   decided instead of guessing.
6. Every event goes to the append-only log.
7. Knowledge is declarative; repairs and quick fixes are deduced from it.
8. Do not over-design.

## How to work

- Pure TDD: a red acceptance test that runs a real refactoring/quick fix over inline sources, the minimal change to
  make it green, then improve the model while green. One commit per cycle.
- Product tests live in each module and use the product alone, in its own vocabulary; acceptance scenarios live in
  `acceptance`. The proposed double loop is in ARCHITECTURE.md, section "Cómo se testea".
- A new dependency between products is a pom change, made in the same commit and reflected in ARCHITECTURE.md.
- Catalog scripts stay within 20 statements and write no code of any language: a statement is said in the body
  language (`ide.program.Body`, `create STATEMENT ${m} "" facts="body=return #{field}"`), and what only a language
  can write is asked of its rules (CatalogTest runs the bodies on the memory projection).
- Adapters have no logic: when one needs something about the domain, it becomes a capability in `session`.

## Architecture and migration

`ARCHITECTURE.md` (Spanish) is the design reference: every module is treated as an autonomous product with its
own domain, with a ficha per module, how it is tested, the decisions taken and a phased migration plan. Read it
before touching a module boundary, and update it in the same commit that changes one. The map above describes the
code as it is today; the fichas describe where it is going.
