package ide.refactor.script;

import java.util.List;

/** Script statements. Call and return arguments are raw text with {@code ${expr}} interpolation. */
sealed interface Stmt {

    record Let(String variable, Expr value) implements Stmt {
    }

    record Call(String variable, String refactoring, List<String> args) implements Stmt {
    }

    record For(String variable, Expr items, List<Stmt> body) implements Stmt {
    }

    record If(Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
    }

    record Try(String label, List<Stmt> body) implements Stmt {
    }

    record Require(Expr condition, String message) implements Stmt {
    }

    record Fail(String message) implements Stmt {
    }

    record Return(String value) implements Stmt {
    }
}
