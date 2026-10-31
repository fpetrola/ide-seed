# IdeSeed

Minimal Java IDE where the program is a graph with stable identity and the text is a projection.
Every structural change is made of a few primitives; correctness comes from verification, not care.

## Build and test

- `mvn test` — whole suite (JUnit 5, AssertJ, ArchUnit). Java 21 (`maven.compiler.release`), runs on JDK 25.
- `mvn test -Dtest=OrdersSampleTest` — one class. Tests tagged `probe` (performance) are excluded by default;
  run them with `mvn test -Dtest=PerformanceProbe -DexcludedGroups=none`.

## Map

- `ide.core` — Capability, CapabilityRegistry, Param, Json, EventLog (JSONL), TextDiff. Knows nothing else.
- `ide.graph` — Element/ElementId/Kind, Reference (id `site@ordinal`, mode read|write|update|call|new|type),
  ProgramGraph (queries), Program (the primitives: create, delete, move, rename, set, retarget), LanguageRules SPI.
- `ide.java` — the Java projection: JavaProject (parse with lexical preservation, graph building, primitives as
  minimal text edits, identity transfer), JavaRules, WorkspaceTypeSolver, JavacCheck, Imports/Text.
- `ide.coherence` — Verifier (binding, access, dispatch views; javac view when the code compiled before),
  Repairer (plans from saved points), Transaction (baseline, intent folding, step tree), Plan/Remedy/QuickFix.
- `ide.refactor` — Refactoring/Ctx/Args, Primitives, ChangeSignature (the only Java composite);
  `ide.refactor.script` — the `.ref` language.
- `ide.knowledge` — goals/effects planner; no remedy or quick fix is written in Java.
- `ide.workbench.Workspace` — transactions, library, undo, log, fixes, completion, navigation, capabilities.

Catalog: `src/main/resources/ide/refactor/standard.ref`. Knowledge: `src/main/resources/ide/knowledge/knowledge.ref`.
Realistic sample: `src/test/resources/samples/orders` (case table in `OrdersSampleTest`).

## Principles (do not break them; say so if an instruction does)

1. The core knows no Java: language semantics live in `ide.java` and in knowledge (ArchUnit enforces it).
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
- Catalog scripts stay within 20 statements (CatalogBudgetTest).
- UI and MCP come last.
