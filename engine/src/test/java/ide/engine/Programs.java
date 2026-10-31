package ide.engine;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;

import java.util.List;
import java.util.function.Function;

/** Remedies and lookups the engine's tests share; no language anywhere. */
final class Programs {

    private Programs() {
    }

    /** For an inaccessible target: one plan, titled with the target, that applies {@code fix}. */
    static Remedy remedy(String title, Function<Violation, Primitive> fix) {
        return (violation, projection) -> violation.code().equals("access.insufficient")
                ? List.of(new Plan(title + " " + projection.program().element(violation.target()).qualified(), List.of(),
                (target, answers) -> target.apply(fix.apply(violation))))
                : List.of();
    }

    /** Makes an inaccessible target package-visible. */
    static Remedy open() {
        return remedy("open", violation -> new Primitive.Set(violation.target(), "visibility", "package"));
    }

    static ElementId id(Program program, String address) {
        return program.find(address).map(Element::id).orElseThrow(() -> new AssertionError("no element " + address));
    }

    static String reference(Program program, String site, String target) {
        var to = id(program, target);
        return program.referencesFrom(id(program, site)).stream().filter(r -> r.target().equals(to)).map(Reference::id)
                .findFirst().orElseThrow();
    }
}
