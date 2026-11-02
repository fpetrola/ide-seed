# Rules and quick fixes

In IdeSeed **no quick fix is written as code**. You write how Java *is*, and what must hold for a problem to go away,
and the planner deduces the fixes. The same knowledge also repairs refactorings: if a refactoring leaves a member
unreachable, the rule that says who can access what proposes the same change a quick fix would.

Two files hold it:

- `knowledge/src/main/resources/ide/knowledge/java.rules`: how Java is. Definitions, constraints and promises,
  each citing the JLS section it states.
- `knowledge/src/main/resources/ide/knowledge/knowledge.ref`: which problem needs what (`goal`), plus notes on the
  rest.

## The language

```
goal <code> needs p(a, b)                       a diagnostic goes away when p holds
problem <code> is rule(expr, expr)              a diagnostic is a rule broken there
def p(x, "constant") when q(x, y) and not r(y, _) and x != y
  and s(y) and given t(x)                       a definition (Datalog)
rule name "what the language says"              a constraint that must hold everywhere
  for v, w where q(v, w) and not r(w)
  must s(v) or not t(w)
end
order name "low" "high" ...                     ordered values: name-value(v), name-at-least(a, b)
say [not] relation for p "title ${...}"         how a change made for p is said: the quick fix's title
promise relation of kind "why" [kept by rule]   a change that only people may choose
ask name "question" for relation of new kind    what to ask about something created
ask name "question" for relation of kind default p
```

Datalog conventions: a bare name is a variable, a quoted text is a constant, `_` is anything. `not` negates and `!=`
compares. A literal marked `given` is only read, never made true.

### How a definition becomes a fix

The planner reads a `def` left to right:

1. A relation of the graph, asked with some arguments known, **binds** the others. For example, `parent(m, t)` with
   `m` known gives `t`.
2. A relation whose arguments are **all known** and that **does not hold** is **made to hold** by the primitive that
   changes it: a visibility is set, a member moved, a use bound to a target, a constructor created.
3. Definitions written first are tried first, and the cheapest plans are offered first.

The graph's relations (`GraphFacts`) include `kind`, `name`, `parent`, `visibility`, `static`, `final`, `abstract`,
`type`, `throws`, `uses`, `writes`, `self-use`, `type-use`, `new-use`, `top`, `top-level`, `package`, `enclosing`,
`subclass`, `superclass`, `overrides`, `constructor`, `member`, `parameter`, `named`, `unresolved`, `site`,
`target`, `scope`, `expected`, `similar`, `declared`, `exists`, `library`, and `known`: what came with the problem,
such as its path, line and symbol.

## Walkthrough: "add throws"

The problem: Eclipse's compiler reports `UnhandledException` when a checked exception is neither caught nor declared.

**1. What must hold** (`knowledge.ref`):

```
goal ecj.UnhandledException needs declares-thrown(inside(path, line, column), first(arguments))
```

The problem comes with its path, line, column and arguments. `inside(...)` is the callable the problem is in, and
`first(arguments)` is the exception's type, as the compiler names it.

**2. How Java is** (`java.rules`):

```
# A checked exception a method or constructor may throw is caught where it is thrown, or the callable declares it
# (JLS 11.2) ...
def declares-thrown(m, e) when kind(m, "method") and throws(m, e)
def declares-thrown(k, e) when kind(k, "constructor") and throws(k, e)
```

`throws(m, e)` has both arguments known and does not hold, so the planner makes it hold with `set ... throws`.

**3. How it is said:**

```
say throws for declares-thrown "add throws ${value} to ${called}"
```

The result is that the window, the CLI and agents are offered `add throws java.io.IOException to read()` on that line.
Applied, it is verified like any change: the error must go and nothing else may change meaning. The import is the
driver's business.

Because declaring an exception keeps meaning, the same rule also **repairs refactorings**. When `extract-method` moves
a throwing statement into a new method, the repairer adds the `throws` by itself.

## Walkthrough: a constraint with a rule

Some knowledge is a constraint over the whole program, not a goal of one problem:

```
# A class that declares an abstract method is abstract (JLS 8.1.1.1).
rule abstract-holder "a class that declares an abstract method is abstract"
  for t, m where kind(t, "class") and parent(m, t) and abstract(m)
  must abstract(t)
end

say abstract for abstract-holder "make ${called} abstract"
```

To connect a compiler diagnostic to a rule, use `problem`:

```
problem ecj.AbstractMethodMustBeImplemented is complete(type-in(path, symbol))
say abstract for complete "make ${called} abstract"
say not unimplemented for complete "implement methods of ${called}"
```

One rule can give several fixes. Each `say` names the change, here making the class abstract or implementing the
methods, and the person chooses.

## Promises: changes only people choose

```
promise final of field "the value never changes once the object is made"
promise static of field "one value for all objects, or one for each"
promise type of method "what it answers is part of how it is called"
```

A plan that changes a promised relation is offered as a quick fix, because a person may choose it. It is never taken
by the repairer on its own, because it changes what the code means for somebody. `kept by rule` relaxes this:

```
promise abstract of class "whether objects of it can be made" kept by no-new-abstract
```

Making a class abstract is fine while nothing creates objects of it.

## Asking the person

Some things only the person knows. The rules propose a value and the window asks for it:

```
def start-of(f, v) when type(f, t) and start-value(t, v)
ask value "initial value" for initialized of field default start-of
ask type "type of the new field" for type of new field
```

The `asks` capability lists the questions, and `fix` takes the answers.

## Recipe: a new quick fix

1. **Reproduce** the problem in an acceptance test under `acceptance/src/test/java/ide/acceptance/fix/`. Use
   `Workspace.of(Map.of("a/X.java", ...))`, then `ws.fixes(path, line)` should contain your title, and after
   `ws.fix(path, line, title)` the code has no errors and reads as expected.
2. Find the diagnostic's **code** with the `diagnostics` capability. Eclipse's codes are written `ecj.<Name>`.
3. Write the **`goal`** (or `problem`) in `knowledge.ref`: what must be true for it to go away.
4. Write or reuse the **`def`s** in `java.rules`, stating how Java is, with the JLS reference. Keep it general: a
   definition about one fix is a smell.
5. Add the **`say`** for the relation the plan changes.
6. If the change breaks a language promise, add or check the **`promise`**, so it stays a person's choice.
7. Run the acceptance test and the whole suite. Repairs elsewhere may get better (or change) because of what you
   taught.

If the planner needs a relation the graph does not have, it belongs in `GraphFacts`, read from `Program` in no
language. Information about one fix's data (what a use needs, where it is) is asked of the projection, not stored in
the graph.

## Testing the rules engine alone

`rules` is a Datalog engine with no idea of programs. `RulesContract` (in `rules/src/test`) shows how to evaluate a set of
clauses and facts directly. This is useful to check that a definition says what you think before wiring it to a
problem.
