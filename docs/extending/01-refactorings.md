# Writing a refactoring

A refactoring in IdeSeed is a short script in the `.ref` language. It composes primitives and other refactorings,
and **it does not need to be careful**. The engine verifies the result and repairs it, or refuses it. The script says
what to do, not how to keep the code correct.

The standard catalog is `refactoring/src/main/resources/ide/refactoring/standard.ref`. Every script there is a
working example.

## A first script

```
refactoring generate-getter "A public method getX that answers the value of a field x"
  param field : element(FIELD) "the field to read"
  getter := create METHOD ${owner(field)} get${cap(name(field))}
  set ${getter} visibility public
  set ${getter} static ${attr(field, "static")}
  set ${getter} type ${type(field)}
  create STATEMENT ${getter} "" facts="body=return #{field}"
  return ${getter}
end
```

Line by line:

- `refactoring <id> "description"` opens it. The id becomes a capability: `generate-getter` is now a CLI command, a
  window action and an MCP tool.
- `param field : element(FIELD) "..."` declares an argument. Callers pass an id or an address (`a.P#x`).
- `getter := create METHOD ...` calls the `create` primitive and keeps the element it produced.
- `${...}` is an expression evaluated against the program: `owner(field)` is the type that declares the field, and
  `cap(name(field))` its name capitalized.
- `create STATEMENT ${getter} "" facts="body=return #{field}"` adds a statement **in the body language**, not in Java:
  `#{field}` names the element, and the Java driver writes `return x;`. This is how a script writes no Java.
- `return ${getter}` is the element the refactoring answers, which the window selects afterwards.

## The script language

```
refactoring <id> ["description"] [changes meaning]
  param <name> : text|lines|bool|element(KINDS)|elements(KINDS)|choice(A,B) ["description"] [default "template"] [from "expr"]
  let v = expr                 a value
  v := <id> args               call a primitive or a refactoring, keep what it answers
  <id> args                    call it
  for x in expr ... end        loop over a list
  if expr ... [else ...] end
  try "label" ... end          an attempt: if it fails, it is undone and the script goes on
  require expr "message"       refuse unless it holds
  fail "message"               refuse
  oblige "assumption"          declare what cannot be verified; it travels with the result as an obligation
  return arg
end
```

- **Arguments** are positional in the order the callee declares its parameters, or `name=value`. A quoted
  argument may contain spaces.
- **Kinds** for `element(...)`: `PACKAGE`, `CLASS`, `INTERFACE`, `ENUM`, `FIELD`, `CONSTRUCTOR`, `METHOD`, `PARAM`,
  `LOCAL`, `STATEMENT`.
- **`default "template"`** may use `${...}`: `default "create${name(owner(constructor))}"`.
- **`changes meaning`** in the header marks a refactoring that changes behavior on purpose (e.g. `split-loop`, `make-field-instance`).
  The verifier still judges it, but the repairer never picks it up by itself.
- `# ` starts a comment line.

### Primitives you can call

| Primitive | Arguments | Example |
|---|---|---|
| `create` | kind, parent, name, `facts=` | `create METHOD ${t} name`, `create PARAM ${m} ${name(p)}` |
| `delete` | element, optional `into` (merge) | `delete ${element}` |
| `move` | element, new parent, optional `before` | `move ${method} ${superclass(owner(method))}` |
| `rename` | element, name | `rename ${m} ${name}` |
| `set` | element, attribute, value | `set ${f} visibility private`, `set ${m} static true`, `set ${m} type int` |
| `link` | element, mode, target | link an `extends` or `implements` |
| `retarget` | reference, new target | `retarget ${r} ${getter}`: a use now binds something else |
| `extract` | method, fragment, name | statements or an expression into a new method |
| `inline` | reference | replace a use by what it stands for |

The library also holds every other refactoring, so scripts call each other: `encapsulate-field` calls
`generate-getter` and `generate-setter`.

### Expressions

Functions over the program, called as `f(x, ...)`:

- About an element: `name`, `kind`, `qualified`, `owner`, `top`, `type`, `declared-type`, `attr(e, "static")`,
  `superclass`, `enclosing`, `body`, `getter-of`.
- Lists: `members`, `fields`, `methods`, `params`, `variables`, `constructors`, `statements`, `subclasses`,
  `family` (a method with everything it overrides and everything that overrides it), `refs` (the uses of an element).
- About a use: `mode(r)` (`"read"`, `"write"`, `"call"`...), `site(r)`, `target(r)`, `statement(r)`.
- Text and logic: `cap`, `decap`, `join`, `concat`, `eq`, `ne`, `not`, `and`, `or`, `empty`, `nonempty`, `count`,
  `first`, `last`, `reversed`.

Lists can be filtered with `|`:

```
for r in refs(field) | not-within top(field)          # uses outside the field's own top-level type
for f in fields(type) | with-attr "visibility=public"
```

Other filters: `within`, `of-kind`, `named`. `ScriptFunctions.java` is the reference for every function.

### The body language

A statement is written in no language and each driver writes it in its own. Elements are named `#{expr}`:

```
return #{field}                          answers a field's value
#{field} = #{p}                          gives a field a value
return new #{constructor}(#{params(f)})  answers a new object (a list is joined with commas)
return #{field}.#{method}(#{p})          answers what a call answers
return default                           answers what the type starts with
add #{value} to #{list}                  puts a value at the end of a list
each #{local} in #{list}                 a loop over a list, empty, declaring the local (create LOCAL it first)
list of #{type}                          a new empty list; also a local's type: facts="type=list of #{type}"
```

A statement is created at the end of a body, or of the one block a statement holds (`create STATEMENT ${loop} ...`);
`move` takes a statement into such a block the same way.

`ide.program.Body` documents the whole language. If you need to say something it cannot, the body language grows,
in `program`. Never write Java text in a script.

## A complete example: encapsulate a field

```
refactoring encapsulate-field "Accessors for a field; uses outside its type go through them; the field becomes private"
  param field : element(FIELD) "the field to encapsulate"
  getter := generate-getter ${field}
  setter := generate-setter ${field}
  for r in refs(field) | not-within top(field)
    if eq(mode(r), "read")
      retarget ${r} ${getter}
    else
      retarget ${r} ${setter}
    end
  end
  set ${field} visibility private
  return ${field}
end
```

`retarget` changes what a use binds and the driver rewrites `p.x = 3` as `p.setX(3)`. Making the field private does
not need to check who still uses it: if anything outside could no longer reach it, the verifier says so, and the
transaction is repaired or refused.

## Testing it

**Acceptance test** (`acceptance/src/test/java/ide/acceptance/refactoring/`), over real Java:

```java
@Test
void generatesAccessorsAndRetargetsOutsideReadsAndWrites() {
    var ws = Workspace.of(Map.of("a/P.java", P, "b/U.java", U));

    var result = ws.invoke("encapsulate-field", Map.of("field", "a.P#x"));

    assertThat(result.violations()).isEmpty();
    assertThat(ws.source("b/U.java")).contains("p.setX(3);", "return p.getX() + 1;");
}
```

Also test what must be **refused**: a case where the refactoring would change behavior should come back with a
violation and leave the sources unchanged.

**Product test** (`refactoring/src/test/java`), with no Java at all, over the in-memory projection:

```java
var projection = MemoryProjection.builder().type("a.A").field("a.A", "x", "int").build();
var run = Run.on(projection, Library.standard());

var getter = run.call("generate-getter", Map.of("field", "a.A#x"));
```

`CatalogTest` runs every catalog body this way. If your script only works with Java, it is writing Java somewhere.

## Trying a script without rebuilding

The `define` capability takes `.ref` text and registers its refactorings in the running IDE. They become
capabilities at once, until the IDE closes. An agent can call `define` through MCP with the script as its `text`,
then call the new refactoring like any other tool. To keep a script, put it in `standard.ref` with its tests.
