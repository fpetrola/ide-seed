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

    /** At {@code offset} of the editor text of {@code path}. */
    public List<String> at(String path, String text, int offset) {
        return projection.completionContext(path, text, offset).map(this::candidates).orElse(List.of());
    }

    /** For what the projection read at a point. */
    public List<String> candidates(CompletionContext context) {
        var program = projection.program();
        var rules = projection.rules();
        var labels = new LinkedHashSet<String>();
        context.locals().stream().filter(local -> local.startsWith(context.prefix())).forEach(labels::add);
        for (var type : program.superclassChain(context.receiver())) {
            for (var member : program.members(type.id())) {
                if (!member.name().startsWith(context.prefix())) continue;
                if (context.staticOnly() && !"true".equals(member.fact(Fact.STATIC))) continue;
                if (!rules.accessible(program, context.site(), member.id())) continue;
                labels.add(member.kind() == Kind.METHOD ? member.name() + "()" : member.name());
            }
        }
        return List.copyOf(labels);
    }
}
