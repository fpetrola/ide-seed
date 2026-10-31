package ide.refactor.script;

import java.util.List;

/** Script expressions: literals, variables, function calls and filter pipelines ({@code list | filter arg}). */
sealed interface Expr {

    record Str(String value) implements Expr {
    }

    record Var(String name) implements Expr {
    }

    record Fn(String name, List<Expr> args) implements Expr {
    }

    record Pipe(Expr source, String filter, Expr arg) implements Expr {
    }
}
