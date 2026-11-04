package ide.java.semantics;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ArrayQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ArrayTypeReference;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedQualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ParameterizedSingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReferenceExpression;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.ast.Wildcard;
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding;
import org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Eclipse's compiler bound each name of a file to, when it compiled it: the same pass that finds its problems
 * resolves every use, as the language says. Each is kept by where its name is written (first and last character),
 * apart for types, constructors and the rest, since {@code new A()} names a type and a constructor with one name. What
 * the compiler could not bind is not here, nor what an error of it touches (to go on, it binds a call that does not apply
 * to the method it almost calls): whoever asks decides on its own.
 */
public final class EcjBindings {

    /**
     * What a name binds: its kind, the type that declares it (a type's own name, for a type), its name, its parameters'
     * types, where it is declared (a local or a parameter), and the package of the type.
     */
    public record Bound(Kind kind, String owner, String name, List<String> parameters, int declaredAt, String pkg) {
    }

    public enum Kind {
        /** {@code owner} is the type, {@code name} what kind: class, interface, enum or annotation. */
        TYPE,
        FIELD,
        METHOD,
        CONSTRUCTOR,
        /** {@code declaredAt} is where the local's name is declared. */
        LOCAL,
        /** {@code declaredAt} is where the parameter's name is declared. */
        PARAM
    }

    /** The bindings of one file. */
    public record File(Map<Long, Bound> names, Map<Long, Bound> types, Map<Long, Bound> constructors) {

        public static final File NONE = new File(Map.of(), Map.of(), Map.of());

        public Bound name(int start, int end) {
            return names.get(key(start, end));
        }

        public Bound type(int start, int end) {
            return types.get(key(start, end));
        }

        /** A constructor called by a new object, by where its type's name is; by a statement, where it starts. */
        public Bound constructor(int start, int end) {
            return constructors.get(key(start, end));
        }
    }

    private EcjBindings() {
    }

    static long key(int start, int end) {
        return ((long) start << 32) | (end & 0xFFFFFFFFL);
    }

    /** What a resolved unit's names bind. */
    static File of(CompilationUnitDeclaration unit) {
        var names = new HashMap<Long, Bound>();
        var types = new HashMap<Long, Bound>();
        var constructors = new HashMap<Long, Bound>();
        var errors = new ArrayList<long[]>();
        var result = unit.compilationResult;
        if (result != null && result.problems != null) {
            for (var i = 0; i < result.problemCount; i++) {
                var problem = result.problems[i];
                if (problem != null && problem.isError()) errors.add(new long[]{problem.getSourceStart(), problem.getSourceEnd()});
            }
        }
        unit.traverse(new ASTVisitor() {
            /** Whether an error is anywhere from {@code start} to {@code end}. */
            private boolean wrong(int start, int end) {
                for (var error : errors) if (error[0] <= end && error[1] >= start) return true;
                return false;
            }

            private void put(Map<Long, Bound> into, long at, Bound bound) {
                if (!wrong((int) (at >>> 32), (int) at)) EcjBindings.put(into, at, bound);
            }

            @Override
            public boolean visit(MessageSend send, BlockScope scope) {
                if (!wrong(send.sourceStart, send.sourceEnd)) put(names, send.nameSourcePosition, method(send.binding));
                return true;
            }

            @Override
            public boolean visit(FieldReference reference, BlockScope scope) {
                put(names, reference.nameSourcePosition, field(reference.binding));
                return true;
            }

            @Override
            public boolean visit(FieldReference reference, ClassScope scope) {
                return visit(reference, (BlockScope) null);
            }

            @Override
            public boolean visit(SingleNameReference reference, BlockScope scope) {
                put(names, key(reference.sourceStart, reference.sourceEnd), value(reference.binding));
                return true;
            }

            @Override
            public boolean visit(SingleNameReference reference, ClassScope scope) {
                return visit(reference, (BlockScope) null);
            }

            @Override
            public boolean visit(QualifiedNameReference reference, BlockScope scope) {
                var first = reference.indexOfFirstFieldBinding - 1; // the token the binding is for
                if (first < 0 || first >= reference.sourcePositions.length) return true;
                put(names, reference.sourcePositions[first], value(reference.binding));
                if (first > 0 && reference.actualReceiverType instanceof ReferenceBinding owner && reference.binding instanceof FieldBinding) {
                    put(names, reference.sourcePositions[first - 1], type(owner));
                }
                if (reference.otherBindings != null) {
                    for (var i = 0; i < reference.otherBindings.length && first + 1 + i < reference.sourcePositions.length; i++) {
                        put(names, reference.sourcePositions[first + 1 + i], field(reference.otherBindings[i]));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedNameReference reference, ClassScope scope) {
                return visit(reference, (BlockScope) null);
            }

            @Override
            public boolean visit(AllocationExpression allocation, BlockScope scope) {
                if (allocation.type != null && !wrong(allocation.sourceStart, allocation.sourceEnd)) {
                    put(constructors, nameOf(allocation.type), constructor(allocation.binding));
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedAllocationExpression allocation, BlockScope scope) {
                if (allocation.anonymousType == null && allocation.type != null && !wrong(allocation.sourceStart, allocation.sourceEnd)) {
                    put(constructors, nameOf(allocation.type), constructor(allocation.binding));
                }
                return true;
            }

            @Override
            public boolean visit(ExplicitConstructorCall call, BlockScope scope) {
                if (!call.isImplicitSuper() && !wrong(call.sourceStart, call.sourceEnd)) {
                    put(constructors, key(call.sourceStart, call.sourceStart), constructor(call.binding));
                }
                return true;
            }

            @Override
            public boolean visit(ReferenceExpression reference, BlockScope scope) {
                if (!wrong(reference.sourceStart, reference.sourceEnd)) {
                    put(names, key(reference.nameSourceStart, reference.sourceEnd), method(reference.getMethodBinding()));
                }
                return true;
            }

            @Override
            public boolean visit(SingleTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(SingleTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ArrayTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ArrayTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ParameterizedSingleTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ParameterizedSingleTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(QualifiedTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(QualifiedTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ArrayQualifiedTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ArrayQualifiedTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ParameterizedQualifiedTypeReference reference, BlockScope scope) {
                typed(reference);
                return true;
            }

            @Override
            public boolean visit(ParameterizedQualifiedTypeReference reference, ClassScope scope) {
                typed(reference);
                return true;
            }

            private void typed(TypeReference reference) {
                var resolved = reference.resolvedType;
                if (resolved instanceof ArrayBinding array) resolved = array.leafComponentType;
                if (resolved instanceof ReferenceBinding type) put(types, nameOf(reference), type(type));
            }
        }, unit.scope);
        return new File(names, types, constructors);
    }

    /** Where the last name of a written type is: {@code B} in {@code a.B<C>[]}. */
    private static long nameOf(TypeReference reference) {
        if (reference instanceof QualifiedTypeReference qualified) return qualified.sourcePositions[qualified.sourcePositions.length - 1];
        var token = reference instanceof SingleTypeReference single ? single.token : null;
        if (token == null) return key(reference.sourceStart, reference.sourceEnd);
        return key(reference.sourceStart, reference.sourceStart + token.length - 1);
    }

    private static void put(Map<Long, Bound> into, long at, Bound bound) {
        if (bound != null) into.putIfAbsent(at, bound);
    }

    private static Bound value(Binding binding) {
        return switch (binding) {
            case FieldBinding field -> field(field);
            case LocalVariableBinding local when local.isValidBinding() && local.declaration != null ->
                    new Bound(local.isParameter() ? Kind.PARAM : Kind.LOCAL, null, new String(local.name), List.of(), local.declaration.sourceStart, null);
            case ReferenceBinding type -> type(type);
            case null, default -> null;
        };
    }

    private static Bound field(FieldBinding field) {
        if (field == null || !field.isValidBinding() || field.declaringClass == null) return null; // an array's length
        var owner = qualified(field.declaringClass);
        return owner == null ? null : new Bound(Kind.FIELD, owner, new String(field.name), List.of(), -1, null);
    }

    private static Bound method(MethodBinding method) {
        if (method == null || !method.isValidBinding() || method.declaringClass == null) return null;
        var declared = method.original();
        var owner = qualified(declared.declaringClass);
        var parameters = parameters(declared);
        if (owner == null || parameters == null) return null;
        return new Bound(declared.isConstructor() ? Kind.CONSTRUCTOR : Kind.METHOD, owner, new String(declared.selector), parameters, -1, null);
    }

    private static Bound constructor(MethodBinding method) {
        var bound = method(method);
        return bound != null && bound.kind() == Kind.CONSTRUCTOR ? bound : null;
    }

    private static Bound type(ReferenceBinding type) {
        if (!type.isValidBinding()) return null;
        var qualified = qualified(type);
        if (qualified == null) return null;
        var kind = type.isAnnotationType() ? "annotation" : type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
        return new Bound(Kind.TYPE, qualified, kind, List.of(), -1, new String(((ReferenceBinding) type.erasure()).qualifiedPackageName()));
    }

    private static List<String> parameters(MethodBinding method) {
        var described = new ArrayList<String>();
        for (var parameter : method.parameters) {
            var one = describe(parameter);
            if (one == null) return null;
            described.add(one);
        }
        return described;
    }

    /** A type as JavaParser's solver describes it, so that a use is keyed as its declaration is. */
    static String describe(TypeBinding type) {
        if (type == null) return null;
        if (type.isBaseType()) return new String(type.sourceName());
        if (type instanceof ArrayBinding array) {
            var leaf = describe(array.leafComponentType);
            return leaf == null ? null : leaf + "[]".repeat(array.dimensions);
        }
        if (type.isTypeVariable()) return new String(type.sourceName());
        if (type instanceof WildcardBinding wildcard) {
            if (wildcard.boundKind == Wildcard.UNBOUND) return "?";
            var bound = describe(wildcard.bound);
            return bound == null ? null : (wildcard.boundKind == Wildcard.EXTENDS ? "? extends " : "? super ") + bound;
        }
        if (type instanceof ParameterizedTypeBinding parameterized && parameterized.arguments != null) {
            var raw = qualified((ReferenceBinding) parameterized.erasure());
            var arguments = new ArrayList<String>();
            for (var argument : parameterized.arguments) {
                var one = describe(argument);
                if (one == null) return null;
                arguments.add(one);
            }
            return raw == null ? null : raw + "<" + String.join(", ", arguments) + ">";
        }
        return type instanceof ReferenceBinding reference ? qualified((ReferenceBinding) reference.erasure()) : null;
    }

    /** A type's qualified name as the source writes it ({@code a.Outer.Inner}); null for a local or anonymous one. */
    static String qualified(ReferenceBinding type) {
        if (type == null || type.isLocalType() || type.isAnonymousType()) return null;
        var erased = (ReferenceBinding) type.erasure();
        var name = new String(erased.qualifiedSourceName());
        var pkg = new String(erased.qualifiedPackageName());
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}
