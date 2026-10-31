package ide.workbench;

import ide.coherence.OpenVisibility;
import ide.coherence.Repairer;
import ide.coherence.Result;
import ide.graph.Program;
import ide.java.JavaProject;
import ide.java.JavaRules;
import ide.refactor.Args;
import ide.refactor.Ctx;
import ide.refactor.ExtractSuperclass;
import ide.refactor.PullUpField;
import ide.refactor.Refactoring;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Entry point: invokes refactorings as transactions over a Java project. */
public final class Workspace {

    private final JavaProject project;
    private final Repairer repairer = new Repairer(new JavaRules(), List.of(new OpenVisibility()));
    private final Map<String, Refactoring> refactorings = Stream.of(new PullUpField(), new ExtractSuperclass())
            .collect(Collectors.toMap(Refactoring::id, Function.identity()));

    private Workspace(JavaProject project) {
        this.project = project;
    }

    public static Workspace of(Map<String, String> sources) {
        return new Workspace(JavaProject.of(sources));
    }

    public String source(String path) {
        return project.source(path);
    }

    /** Runs a refactoring naively, then verifies and repairs; rolls back if violations remain. */
    public Result invoke(String refactoring, Map<String, ?> args) {
        var snapshot = project.snapshot();
        var baseline = project.graph();
        var ctx = new Ctx() {
            @Override
            public Program program() {
                return project;
            }

            @Override
            public void call(String id, Map<String, ?> callArgs) {
                refactoring(id).apply(this, new Args(callArgs));
            }
        };
        ctx.call(refactoring, args);
        var outcome = repairer.stabilize(baseline, project);
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs());
        project.restore(snapshot);
        return Result.aborted(outcome.violations());
    }

    private Refactoring refactoring(String id) {
        var refactoring = refactorings.get(id);
        if (refactoring == null) throw new IllegalArgumentException("unknown refactoring: " + id);
        return refactoring;
    }
}
