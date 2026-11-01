package ide.session;

import ide.program.Fact;
import ide.program.Kind;
import ide.projection.CompletionContext;
import ide.projection.Projection;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * What may be written at a point of a file's text: the locals in scope first, then the accessible fields ({@code x})
 * and methods ({@code m()}) of the receiver, or of the enclosing type, and of its superclasses, nearest first and
 * overridden methods once; only static ones on a type name.
 */
public final class Completion {

    private final Projection projection;

    public Completion(Projection projection) {
        this.projection = projection;
    }

    /** What is offered: the text written, and the kind of what it names (LOCAL, FIELD or METHOD). */
    public record Candidate(String label, String kind) {
    }

    /** At {@code offset} of the editor text of {@code path}. */
    public List<String> at(String path, String text, int offset) {
        return options(path, text, offset).stream().map(Candidate::label).toList();
    }

    /** At {@code offset} of the editor text of {@code path}, each with its kind. */
    public List<Candidate> options(String path, String text, int offset) {
        return projection.completionContext(path, text, offset).map(this::found).orElse(List.of());
    }

    /** For what the projection read at a point. */
    public List<String> candidates(CompletionContext context) {
        return found(context).stream().map(Candidate::label).toList();
    }

    private List<Candidate> found(CompletionContext context) {
        var program = projection.program();
        var rules = projection.rules();
        var labels = new LinkedHashSet<Candidate>();
        context.locals().stream().filter(local -> local.startsWith(context.prefix())).forEach(local -> labels.add(new Candidate(local, "LOCAL")));
        for (var type : program.superclassChain(context.receiver())) {
            for (var member : program.members(type.id())) {
                if (!member.name().startsWith(context.prefix())) continue;
                if (context.staticOnly() && !"true".equals(member.fact(Fact.STATIC))) continue;
                if (!rules.accessible(program, context.site(), member.id())) continue;
                labels.add(new Candidate(member.kind() == Kind.METHOD ? member.name() + "()" : member.name(), member.kind().name()));
            }
        }
        return List.copyOf(labels);
    }
}
