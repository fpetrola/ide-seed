package ide.java.primitives;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import ide.java.semantics.Bodies;
import ide.program.Change;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.PrimitiveRejected;

/**
 * Replaces a read of a local by the value it is given where it is declared, parenthesized where the read needs it.
 * What would change what the program does without changing what a name binds, which the verifier cannot see, is
 * refused here: a local given another value afterwards, a value of another type than the local, a value read after
 * what it reads may have changed, and a read in a lambda or a class, which happens later. A value with effects moves
 * from its declaration to the read, when it is read once and nothing between could tell; else it is refused. The change
 * says the read is gone and that the value's uses, as they bind where it is declared, are made where it was: a name
 * there that binds something else is a capture.
 */
final class InlineLocal {

    private InlineLocal() {
    }

    static Change apply(Primitive.Inline inline, Context c, Program before, Reference read) {
        var local = before.element(read.target());
        if (before.referencesTo(local.id()).stream().anyMatch(r -> r.mode() != Mode.READ)) {
            throw new PrimitiveRejected("inline.reassigned", local.qualified() + " is given another value after it is declared");
        }
        var declaration = c.declaration(local);
        var variable = declaration.as(VariableDeclarator.class);
        var value = variable.getInitializer().orElseThrow(() ->
                new PrimitiveRejected("inline.no-value", local.qualified() + " is given no value where it is declared"));
        if (!(c.use(read.id()) instanceof NameExpr use)) throw new PrimitiveRejected("inline.not-a-read", read.id() + " does not read " + local.name());
        var effect = Bodies.effect(value);
        var moves = effect != null; // it runs once, where it is read: it is read once, and nothing between could tell
        if (moves && before.referencesTo(local.id()).size() > 1) {
            throw new PrimitiveRejected("inline.effects", "'" + effect + "' would run where " + local.name() + " is read, each time");
        }
        var past = moves ? Bodies.movedPast(variable, use) : null;
        if (past != null) throw new PrimitiveRejected("inline.effects", "'" + effect + "' would run where " + local.name() + " is read, after or without '" + past + "'");
        var other = otherType(variable, value);
        if (other != null) throw new PrimitiveRejected("inline.type", "'" + value + "' is " + other + ", " + local.name() + " is " + variable.getType());
        var deferred = Bodies.deferred(variable, use);
        if (deferred != null) throw new PrimitiveRejected("inline.deferred", local.name() + " is read later, in '" + deferred + "'");
        var changed = Bodies.changedBetween(variable, value, use);
        if (changed != null) throw new PrimitiveRejected("inline.changed", "'" + changed + "' may change what " + local.name() + " is given");
        var change = inline.intent(before, null);
        var range = value.getRange().orElseThrow();
        for (var made : before.referencesFrom(read.site())) {
            if (c.use(made.id()).getRange().filter(range::contains).isPresent()) {
                change = change.expect(new Change.Added(read.site(), made.target(), made.name(), made.mode()));
            }
        }
        var unit = declaration.unit();
        var text = Bodies.needsParentheses(value, use) ? "(" + unit.textOf(value) + ")" : unit.textOf(value);
        if (moves) { // the value leaves its declaration, and the uses it made there with it
            for (var made : before.referencesFrom(read.site())) {
                if (c.use(made.id()).getRange().filter(range::contains).isPresent()) change = change.expect(new Change.Dropped(made.site(), made.target()));
            }
        }
        c.sources().apply(Shared.edits(e -> {
            e.replace(unit, use, text);
            if (moves) e.replace(unit, ide.java.text.Unit.after(variable.getName().getEnd().orElseThrow()), value.getEnd().orElseThrow(), "");
        }));
        return change;
    }

    /** The value's type when it is not the local's (a read would see another), or null; what cannot be told is other. */
    private static String otherType(VariableDeclarator variable, Expression value) {
        if (variable.getType().isVarType()) return null;
        try {
            var given = value.calculateResolvedType().describe();
            return variable.getType().resolve().describe().equals(given) ? null : given;
        } catch (RuntimeException unknown) {
            return "of a type that cannot be told";
        }
    }
}
