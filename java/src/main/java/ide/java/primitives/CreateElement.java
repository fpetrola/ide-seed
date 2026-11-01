package ide.java.primitives;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ide.java.syntax.Lists;
import ide.java.syntax.Members;
import ide.java.syntax.Render;
import ide.java.text.EditSet;
import ide.program.Address;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creates an element: a package (before it has files), a type in a file of its own, an {@code Object} field after
 * the last field, an empty {@code void} method at the end of a type, or an {@code Object} parameter at the end of a
 * method, which every call passes {@code argument} for. The facts it is born with are set right after.
 */
final class CreateElement {

    private CreateElement() {
    }

    static Change apply(Primitive.Create create, Context c, Program before) {
        var created = create(c, create.kind(), create.parent(), create.name(), create.argument());
        create.facts().forEach((fact, value) -> SetAttribute.set(c, created, fact.label(), value));
        return create.intent(before, created);
    }

    private static ElementId create(Context c, Kind kind, ElementId parent, String name, String argument) {
        var ids = c.ids();
        if (kind == Kind.PACKAGE) {
            for (var part : name.split("\\.", -1)) Shared.requireIdentifier(part);
            if (ids.has(new Address.Package(name))) throw new PrimitiveRejected("projection.duplicate", name + " already exists");
            c.declarePackage(name);
            return ids.of(new Address.Package(name), Kind.PACKAGE);
        }
        Shared.requireIdentifier(name);
        var owner = c.program().element(parent);
        Address address = switch (kind) {
            case CLASS, INTERFACE -> new Address.Type(owner.qualified() + "." + name);
            case FIELD -> new Address.Field(owner.id(), name);
            case METHOD -> new Address.Callable(owner.id(), name, List.of());
            case CONSTRUCTOR -> {
                if (!name.equals(owner.name())) throw new PrimitiveRejected("create.constructor", "a constructor of " + owner.name() + " is named " + owner.name());
                yield new Address.Callable(owner.id(), "new", List.of());
            }
            case PARAM -> new Address.Param(owner.id(), name);
            default -> throw new IllegalArgumentException("unsupported create: " + kind);
        };
        if (ids.has(address)) throw new PrimitiveRejected("projection.duplicate", ids.render(address) + " already exists");
        var sources = c.sources();
        switch (kind) {
            case CLASS, INTERFACE -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("types go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (sources.has(path)) throw new PrimitiveRejected("projection.duplicate", path + " already exists");
                sources.put(Map.of(path, Render.typeFile(owner.qualified(), kind == Kind.CLASS ? "class" : "interface", name)));
            }
            case FIELD -> {
                var type = c.declaration(owner);
                sources.apply(Shared.edits(e -> Members.appendField(e, type.unit(), type.as(TypeDeclaration.class), "Object " + name + ";")));
            }
            case METHOD -> {
                var type = c.declaration(owner);
                var indent = Members.indentation(type.unit(), type.as(TypeDeclaration.class));
                sources.apply(Shared.edits(e -> Members.append(e, type.unit(), type.as(TypeDeclaration.class), Render.emptyMethod(name, indent))));
            }
            case CONSTRUCTOR -> {
                var type = c.declaration(owner);
                var indent = Members.indentation(type.unit(), type.as(TypeDeclaration.class));
                sources.apply(Shared.edits(e -> Members.append(e, type.unit(), type.as(TypeDeclaration.class), Render.emptyConstructor(name, indent))));
            }
            default -> { // a parameter
                var calls = Shared.callsTo(c, owner.id());
                if (!calls.isEmpty() && argument == null) {
                    throw new PrimitiveRejected("create.argument", "the calls of " + owner.qualified() + " need an argument for " + name);
                }
                var declaration = c.declaration(owner);
                com.github.javaparser.ast.body.CallableDeclaration<?> method = declaration.as(com.github.javaparser.ast.body.CallableDeclaration.class);
                var edits = new EditSet();
                Lists.append(edits, declaration.unit(), method.getParameters(), method.getName(), "Object " + name);
                for (var call : calls) Lists.append(edits, sources.unitOf(call.node()), call.arguments(), call.name(), argument);
                var names = new ArrayList<>(Shared.parameterNames(method));
                names.add(name);
                sources.apply(edits);
                Shared.resign(c, owner, declaration.unit().path(), names);
            }
        }
        return ids.of(address, kind.isType() ? Kind.CLASS : kind);
    }
}
