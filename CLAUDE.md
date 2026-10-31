# IdeSeed

Minimal Java IDE where the program is a graph with stable identity and the text is a projection.
Every structural change is made of a few primitives; correctness comes from verification, not care.

## Build and test

- `mvn test` from the root builds every module and runs every suite (JUnit 5, AssertJ, ArchUnit).
  Java 21 (`maven.compiler.release`), runs on JDK 25.
- `mvn test -Dtest=OrdersSampleTest` runs one class wherever it lives; `mvn test -pl program` runs one product's
  suite (add `-am` when the products below it changed).
- Tests tagged `probe` (performance) are excluded by default; run them with
  `mvn test -Dtest=PerformanceProbe -DexcludedGroups=none`.

## Map

One Maven module per product; each pom declares only the products it uses, so the poms are the dependency map and
the compiler enforces it. Folder `<product>/`, artifact `ide-<product>`, package `ide.<product>`.

- `core` — Capability, CapabilityRegistry, Param, Json, EventLog (JSONL), TextDiff. Knows nothing else.
- `program` (core) — the graph: Element/ElementId/Kind, Fact (typed facts), Reference (id `site@ordinal`, Mode:
  uses read|write|update|call|new|type and links extends|implements|declared), Program (indexed queries, diff,
  hypothetical copies), Address (typed: types by qualified name, members by owner id, callables by parameter type
  keys) and Identity (address → id; rebindType, rename, move, resign, forget).
- `projection` (program) — the language driver contract: Projection (the primitives: create, delete, move, rename,
  set, retarget, extract, inline, replaceSource; checkpoint/rollback; diagnostics; completion), LanguageRules,
  Diagnostic, CompletionContext, PrimitiveRejected.
- `java` (projection, JavaParser) — the Java driver: JavaProject (units, primitives as minimal text edits, re-parse
  after each), GraphReader (packages and types, then members, then references), Keys (parameter type keys),
  Resolution (kinds, modes, facts), Bodies (data flow and precedence for extract/inline), JavaRules,
  WorkspaceTypeSolver, JavacCheck, Imports/Text. The only module that compiles against JavaParser.
- `engine` (projection) — Verifier (binding, access, dispatch views; javac view when the code compiled before),
  Repairer (plans from saved points), Transaction (baseline, intent folding, step tree), Plan/Remedy/QuickFix.
- `refactoring` (projection) — Refactoring/Ctx/Args, Primitives, ChangeSignature (the only Java composite); the `.ref`
  language in `ide.refactoring.script`; the catalog `refactoring/src/main/resources/ide/refactoring/standard.ref`.
- `knowledge` (engine, refactoring) — goals/effects planner over
  `knowledge/src/main/resources/ide/knowledge/knowledge.ref`; no remedy or quick fix is written in Java.
- `session` (java, engine, refactoring, knowledge; JavaParser only at run time) — `Workspace`: transactions,
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
