package ide.workbench;

import ide.coherence.Result;
import ide.coherence.OpenVisibility;
import ide.coherence.Repairer;
import ide.graph.ElementId;
import ide.java.JavaProject;
import ide.java.JavaRules;

import java.util.List;
import java.util.Map;

/** Entry point: invokes capabilities as transactions over a Java project. */
public final class Workspace {

    private final JavaProject project;
    private final Repairer repairer = new Repairer(new JavaRules(), List.of(new OpenVisibility()));

    private Workspace(JavaProject project) {
        this.project = project;
    }

    public static Workspace of(Map<String, String> sources) {
        return new Workspace(JavaProject.of(sources));
    }

    public String source(String path) {
        return project.source(path);
    }

    public Result invoke(String capability, Map<String, String> args) {
        if (!capability.equals("pull-up-field")) throw new IllegalArgumentException("unknown capability: " + capability);
        var snapshot = project.snapshot();
        var baseline = project.graph();
        pullUpField(args.get("field"));
        var outcome = repairer.stabilize(baseline, project);
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs());
        project.restore(snapshot);
        return Result.aborted(outcome.violations());
    }

    /** Naive: just moves the field to the superclass; the verifier judges the outcome. */
    private void pullUpField(String address) {
        var graph = project.graph();
        var field = graph.find(address).orElseThrow(() -> new IllegalArgumentException("no element " + address));
        ElementId owner = field.parent();
        var superclass = graph.element(owner).attribute("superclass");
        var target = graph.find(superclass).orElseThrow(() -> new IllegalArgumentException("no type " + superclass));
        project.move(field.id(), target.id());
    }
}
