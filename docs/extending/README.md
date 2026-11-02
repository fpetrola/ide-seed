# Extending IdeSeed

These tutorials show how to add to the IDE without fighting its design. Read them in order the first time:

1. [Writing a refactoring](01-refactorings.md): a `.ref` script made of primitives and other refactorings.
2. [Writing an intent](02-intents.md): a refactoring that only says what must hold at the end.
3. [Rules and quick fixes](03-rules-and-quick-fixes.md): teaching the IDE how Java is, so it deduces quick fixes
   and repairs.
4. [Adding a capability](04-capabilities.md): a new operation for the CLI, the window and AI agents at once.

## The mental model in one page

- **The program is a graph.** Types, members, parameters, locals and the top-level statements of each body are
  *elements* with stable ids. Uses are *references* from a site to a target, with a mode (`read`, `write`, `call`,
  `new`, `type`, `extends`...). You name an element by its address: `a.P` (a type), `a.P#x` (a field),
  `a.P#get()` (a method, by its parameter types), `a.Config#read(Path)`.
- **Every change is made of primitives**: `create`, `delete`, `move`, `rename`, `set`, `link`, `retarget`, `extract`,
  `inline`. The Java driver writes each one as the smallest text edit that does it.
- **Every operation is verified.** It runs in a transaction. Afterwards the code is resolved again and compared with
  what it was: what each use binds to, who can access what, which method each call reaches, and the compiler's errors.
  What changed meaning is repaired from the knowledge, or the whole operation is undone with the reason.
- **Knowledge is declarative.** How Java works lives in `java.rules`, written in Datalog. Quick fixes and repairs
  are *deduced* from it. There is no Java class per quick fix, and there should never be one.
- **Capabilities are the only door.** A capability is a JSON schema plus an invocation. The CLI, the window and the MCP
  server only translate, so anything you add as a refactoring or capability reaches all three, agents included.

## Where each kind of extension goes

| You want to... | Write | In |
|---|---|---|
| Combine existing steps into a new refactoring | a `refactoring` script | `refactoring/src/main/resources/ide/refactoring/standard.ref` |
| Ask for an end state and let the IDE find the steps | an `intent` script | the same file |
| Offer a quick fix for a compiler error or a broken rule | `goal`/`problem`, `def`, `say` | `knowledge/src/main/resources/ide/knowledge/` |
| Make the IDE know a new fact about Java | a `def` or a `rule` | `java.rules` |
| A new operation that is not a refactoring (a query, a view, a tool) | a capability | `session` (`Capabilities.java`) |
| A change no primitive can express | a new primitive (rare; it must not be composable) | `program` (`Primitive`), then each driver |
| A new language | a projection (driver) | a new module that passes `ProjectionContract` from `projection-kit` |

## How to work

The project is built test first, and extensions should be too:

1. Write a **failing acceptance test** in `acceptance/src/test/java/ide/acceptance/...`. It opens a workspace over
   inline Java sources, runs the real refactoring or quick fix, and asserts the resulting text and that nothing
   broke.
2. Make the **smallest change** that makes it pass, usually a few lines of `.ref` or of rules.
3. Improve while green, and commit.

```sh
mvn test -Dtest=EncapsulateFieldTest   # one class, wherever it lives
mvn test                               # everything
```

Rules the code base holds itself to, and that reviews will check:

- Catalog scripts write **no code of any language** and stay within **20 statements**. `CatalogTest` checks both.
- Adapters (`cli`, `ui`, `mcp`) contain **no logic**. If they need to know something about the domain, that becomes a
  capability in `session`.
- **No check is switched off** to make something pass. If the verifier refuses a change, the change is wrong, or the
  knowledge is missing something.
