package ide.refactoring;

import ide.core.Param;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Primitive;
import ide.program.Program;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every primitive of the vocabulary as a refactoring, so scripts and adapters call them like any other. Nothing is
 * registered by hand: the id is the record's name, the parameters are its components, the description its summary.
 * A primitive answers the element it produced, else the first element it was given.
 */
public final class Primitives {

    private Primitives() {
    }

    public static List<Refactoring> all() {
        return Arrays.stream(Primitive.class.getPermittedSubclasses()).map(Primitives::refactoring).toList();
    }

    private static Refactoring refactoring(Class<?> type) {
        var components = type.getRecordComponents();
        var params = Arrays.stream(components).map(Primitives::param).toList();
        var constructor = canonical(type, components);
        var id = type.getSimpleName().toLowerCase();
        var summary = type.getAnnotation(Primitive.Summary.class).value();
        return new Refactoring() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return summary;
            }

            @Override
            public List<Param> params() {
                return params;
            }

            @Override
            public String level() {
                return "primitive";
            }

            @Override
            public String apply(Run run, Args args) {
                var program = run.program();
                var values = new Object[components.length];
                for (var i = 0; i < components.length; i++) values[i] = value(components[i], args, program);
                var change = run.projection().apply(construct(constructor, values));
                if (change.produced() != null) return change.produced().value();
                return Arrays.stream(values).filter(ElementId.class::isInstance).map(v -> ((ElementId) v).value())
                        .findFirst().orElse(null);
            }
        };
    }

    /** A component as a parameter: elements by id or address, enums as choices, facts as {@code fact=value; ...}. */
    private static Param param(RecordComponent component) {
        var type = component.getType();
        var param = ElementId.class.equals(type) ? Param.of(component.getName(), "element")
                : type.isEnum() ? Param.of(component.getName(), "choice",
                Arrays.stream(type.getEnumConstants()).map(Object::toString).toArray(String[]::new))
                : Map.class.equals(type) ? Param.of(component.getName(), "facts")
                : Param.of(component.getName(), "text");
        return component.isAnnotationPresent(Primitive.Optional.class) ? param.optional() : param;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object value(RecordComponent component, Args args, Program program) {
        var name = component.getName();
        var type = component.getType();
        var given = args.values().get(name);
        if (given == null) {
            if (!component.isAnnotationPresent(Primitive.Optional.class)) throw new IllegalArgumentException("missing argument: " + name);
            return null;
        }
        if (ElementId.class.equals(type)) return args.element(name, program).id();
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, String.valueOf(given).toUpperCase());
        if (Map.class.equals(type)) return facts(given);
        return String.valueOf(given);
    }

    /** Facts given as a map, or as text {@code type=int; visibility=public}. */
    private static Map<Fact, String> facts(Object given) {
        var facts = new LinkedHashMap<Fact, String>();
        if (given instanceof Map<?, ?> map) {
            map.forEach((k, v) -> facts.put(k instanceof Fact f ? f : Fact.of(String.valueOf(k)), String.valueOf(v)));
        } else {
            for (var pair : String.valueOf(given).split(";")) {
                if (pair.isBlank()) continue;
                var eq = pair.indexOf('=');
                facts.put(Fact.of(pair.substring(0, eq).strip()), pair.substring(eq + 1).strip());
            }
        }
        return facts;
    }

    private static Constructor<?> canonical(Class<?> type, RecordComponent[] components) {
        try {
            return type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Primitive construct(Constructor<?> constructor, Object[] values) {
        try {
            return (Primitive) constructor.newInstance(values);
        } catch (InvocationTargetException invalid) {
            if (invalid.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(invalid);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
