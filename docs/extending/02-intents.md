# Writing an intent

An intent is a refactoring that only says **what must be true at the end**. The knowledge's planner finds the
primitives that make it true. It also finds what else has to change for the code to still compile and still do the
same, and the verifier checks the result as for any refactoring.

Write an intent when the steps depend on the code. Pulling a field up, for instance, may need a constructor to pass
its value, a visibility to change, or a dependency to go up too. A script would have to foresee each case; an intent
gets them from the rules.

## Example: pull up a field

```
intent pull-up-field "Moves a field to the superclass of its type"
  param field : element(FIELD) "the field to move up"
  want given parent(field, t) and given superclass(t, u) and parent(field, u)
end
```

- `param` works as in a refactoring.
- `want` is one condition, written in the same Datalog as `java.rules` (see [rules](03-rules-and-quick-fixes.md)).
  It continues on lines that start with `and`.
- `given` marks a **condition**, something read and never changed. `given parent(field, t)` binds `t` to the field's
  current type, and `given superclass(t, u)` binds `u` to its superclass.
- The last literal, `parent(field, u)`, does not hold yet, so the planner makes it hold with the primitive that
  changes a parent: `move`.

Then it mends what that breaks, rule by rule, in the same plan. If the subclass's constructor assigned a final field,
`java.rules` says how (`final-where` and `given-by-its-type`): the superclass's constructor takes the value as a
parameter and the subclass passes it. Nothing about constructors is written in the intent.

## Example: extract a superclass

```
intent extract-superclass "Creates a class between a type and its superclass and pulls fields up into it"
  param type : element(CLASS) "the type to extract from"
  param name : text "name of the new superclass" default "Abstract${name(type)}"
  param fields : elements(FIELD) "fields to pull up" from "fields(type)"
  want given parent(type, p) and given not member(_, p, name, _) and member(b, p, name, "class")
    and in-between(b, type) and superclass(type, b) and each f in fields: parent(f, b)
end
```

- `given not member(_, p, name, _)` refuses the intent if the package already has something by that name.
- `member(b, p, name, "class")` does not hold, so a class is created and `b` is bound to it.
- `in-between(b, type)` is a definition from `java.rules`: the new class extends what `type` extended.
- `each f in fields: parent(f, b)` asks for every chosen field to end in `b`.
- `from "fields(type)"` on a parameter gives the window and agents the list to choose from.

## How the planner decides

- A plan has at most 4 effects for what the want asks for. Repairs of what those break do not count toward the 4.
- Among plans that **keep meaning**, the cheapest wins. A plan that would break a `promise` of the language, such as a
  `final`, a field's `static` or a type, is not taken by itself.
- If no plan that keeps meaning exists, the want alone is applied and the verifier and repairer judge it as for any
  refactoring. That may end in a refusal with the reason.
- The search is recorded in the result's trace, so you can see why a plan was or was not taken.

## When not to write an intent

If the steps are always the same (`generate-getter`, `move-members`), a plain refactoring is clearer and cheaper.
Intents are for when the right steps depend on the code around the change.

## Testing

As for refactorings: an acceptance test that runs the intent over Java with the cases that make it interesting,
such as a final field, a constructor or a private dependency. Then assert the text, the absence of violations, and
the refusals. See `ExtractSuperclassTest` and `PullUpFieldTest` in `acceptance`.
