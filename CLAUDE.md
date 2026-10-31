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

## Map

One Maven module per product; each pom declares only the products it uses, so the poms are the dependency map and
the compiler enforces it. Folder `<product>/`, artifact `ide-<product>`, package `ide.<product>`.

- `core` — Capability, CapabilityRegistry, Param, Json, EventLog (JSONL), TextDiff. Knows nothing else.
- `program` (core) — the graph: Element/ElementId/Kind, Fact (typed facts), Reference (id `site@ordinal`, Mode:
  uses read|write|update|call|new|type and links extends|implements|declared), Program (indexed queries, diff,
  `expecting(Change)`, `outline()`), Address (typed: types by qualified name, members by owner id, callables by
  parameter type keys) and Identity (address → id; rebindType, rename, move, resign, forget). The vocabulary of
  change: Primitive (sealed records Create, Delete, Move, Rename, Set, Link, Retarget, Extract, Inline; each states
  its `intent`) and Change (produced element, expectations, pins).
- `projection` (program) — the language driver contract: Projection (`program`, `apply(Primitive)` → Change, `edit`,
  typed `snapshot`/`restore`, `rules`, `diagnostics`, `completionContext`), LanguageRules, Diagnostic,
  CompletionContext, PrimitiveRejected.
- `projection-kit` (projection, JUnit, AssertJ) — the kit for driver writers: ProjectionContract (the obligations as
  tests over a fixture each driver writes in its language) and MemoryProjection (a projection with no language,
  with a Builder; the stand-in for a driver in the tests of engine and refactoring).
- `java` (projection, JavaParser) — the Java driver, in layers that only know the ones below (LayersTest):
  `ide.java.text` (Unit, EditSet, Sources: files change only as text, all or nothing; the only place that parses),
  `ide.java.syntax` (Decl, Members, Modifiers, Lists, Imports, Render: Java constructs written as text),
  `ide.java.semantics` (Reader: program + declarations + use nodes; Resolution, Keys, WorkspaceTypeSolver,
  JavacCheck, Bodies, Completion), `ide.java.primitives` (Context and one class per primitive), and the facade
  `ide.java.JavaProjection` (sources, identity, reading, invalidation). The AST is never edited. Other modules use
  only the facade (ArchitectureTest). Runs ProjectionContract in its tests.
- `engine` (projection) — Transaction (intercepts `apply`, folds each declared Change into the baseline, pins),
  Verifier (a list of Views compared through a Comparison: BindingView, AccessView, DispatchView, and CompilerView,
  costly, last), Repairer (plans from saved points), Trace (the tree of what ran or was searched; `lines()` is the
  only place that writes sigils), Result, Plan/Remedy/QuickFix. Tested alone over MemoryProjection.
- `refactoring` (projection) — Refactoring/Ctx/Args, Primitives (read off the Primitive records: nothing registered
  by hand), ChangeSignature (the only Java composite); the `.ref`
  language in `ide.refactoring.script`; the catalog `refactoring/src/main/resources/ide/refactoring/standard.ref`.
- `knowledge` (engine, refactoring) — goals/effects planner over
  `knowledge/src/main/resources/ide/knowledge/knowledge.ref`; no remedy or quick fix is written in Java.
- `session` (java, engine, refactoring, knowledge; JavaParser only at run time) — `Workspace` over a
  `JavaProjection`: transactions,
  library, undo, log, fixes, completion, navigation, capabilities.
- `acceptance` (session, tests only) — not a product: scenarios across every product (refactorings, quick fixes,
  completion over Java text), the samples in `acceptance/src/test/resources/samples` (case tables in
  `OrdersSampleTest` and `ShopSampleTest`), and the two ArchUnit rules Maven cannot state.

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
- Catalog scripts stay within 20 statements (CatalogBudgetTest).
- UI and MCP come last.

## Architecture and migration

`ARCHITECTURE.md` (Spanish) is the design reference: every module is treated as an autonomous product with its
own domain, with a ficha per module, how it is tested, the decisions taken and a phased migration plan. Read it
before touching a module boundary, and update it in the same commit that changes one. The map above describes the
code as it is today; the fichas describe where it is going.
