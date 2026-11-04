package ide.java.semantics;

import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.LambdaExpression;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.ProblemReasons;
import org.eclipse.jdt.internal.compiler.lookup.ProblemMethodBinding;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
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
        PARAM,
        /** It surely binds nothing: {@code owner} is the type it is looked up in, if known. */
        NOTHING
    }

    /** The bindings of one file, and the methods and constructors it declares, by where their names are. */
    public record File(Map<Long, Bound> names, Map<Long, Bound> types, Map<Long, Bound> constructors, Map<Long, Bound> declared) {

        public static final File NONE = new File(Map.of(), Map.of(), Map.of(), Map.of());

        /** A method or constructor this file declares, with its parameters' types, by where its name is. */
        public Bound declaration(int start, int end) {
            return declared.get(key(start, end));
        }

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
        var declared = new HashMap<Long, Bound>();
        var errors = new ArrayList<long[]>(); // where the compiler bound what does not apply to what it almost calls, to go on
        var missingTypes = new ArrayList<long[]>(); // where a type is nowhere
        var result = unit.compilationResult;
        if (result != null && result.problems != null) {
            for (var i = 0; i < result.problemCount; i++) {
                var problem = result.problems[i];
                if (problem == null || !problem.isError()) continue;
                if (APPLIES_NOT.contains(problem.getID())) errors.add(new long[]{problem.getSourceStart(), problem.getSourceEnd()});
                if (problem.getID() == org.eclipse.jdt.core.compiler.IProblem.UndefinedType) {
                    missingTypes.add(new long[]{problem.getSourceStart(), problem.getSourceEnd()});
                }
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
                if (!wrong((int) (send.nameSourcePosition >>> 32), (int) send.nameSourcePosition)) put(names, send.nameSourcePosition, method(send.binding));
                else if (send.binding instanceof ProblemMethodBinding problem && problem.problemId() == ProblemReasons.NotFound
                        && problem.closestMatch == null) { // no method of that name where it is looked up
                    EcjBindings.put(names, send.nameSourcePosition, nothing(send.actualReceiverType, scope));
                }
                return true;
            }

            /**
             * A lambda passed where a type is inferred is resolved as copies, one for each type tried: the one for its
             * type says what its names bind (the same text, the same places); what the lambda itself bound fills the rest.
             */
            @Override
            public boolean visit(LambdaExpression lambda, BlockScope scope) {
                var copy = resolvedCopy(lambda);
                if (copy != null && copy != lambda) copy.traverse(this, scope);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration method, ClassScope scope) {
                declare(method);
                return true;
            }

            @Override
            public boolean visit(ConstructorDeclaration constructor, ClassScope scope) {
                if (!constructor.isDefaultConstructor()) declare(constructor);
                return true;
            }

            private void declare(AbstractMethodDeclaration declaration) {
                var bound = method(declaration.binding);
                if (bound == null && declaration.binding != null && declaration.binding.declaringClass != null) { // a type that is not there
                    var parameters = parameters(declaration.binding);
                    var owner = qualified(declaration.binding.declaringClass);
                    if (parameters != null && owner != null) {
                        bound = new Bound(declaration.isConstructor() ? Kind.CONSTRUCTOR : Kind.METHOD, owner,
                                new String(declaration.selector), parameters, -1, null);
                    }
                }
                EcjBindings.put(declared, key(declaration.sourceStart, declaration.sourceStart + declaration.selector.length - 1), bound);
            }

            @Override
            public boolean visit(FieldReference reference, BlockScope scope) {
                put(names, reference.nameSourcePosition, field(reference.binding));
                if (notFound(reference.binding) && reference.actualReceiverType instanceof ReferenceBinding receiver) {
                    EcjBindings.put(names, reference.nameSourcePosition, nothing(receiver, scope));
                }
                return true;
            }

            @Override
            public boolean visit(FieldReference reference, ClassScope scope) {
                return visit(reference, (BlockScope) null);
            }

            @Override
            public boolean visit(SingleNameReference reference, BlockScope scope) {
                put(names, key(reference.sourceStart, reference.sourceEnd), value(reference.binding));
                if (notFound(reference.binding)) {
                    EcjBindings.put(names, key(reference.sourceStart, reference.sourceEnd), nothing(null, scope));
                }
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
                if (first > 0 && reference.binding instanceof ReferenceBinding named) { // a type's name: Outer in Outer.Inner
                    var enclosing = named.enclosingType();
                    for (var i = first - 1; i >= 0 && enclosing != null; i--, enclosing = enclosing.enclosingType()) {
                        put(names, reference.sourcePositions[i], type(enclosing));
                    }
                }
                if (first > 0 && reference.actualReceiverType instanceof ReferenceBinding owner && reference.binding instanceof FieldBinding) {
                    put(names, reference.sourcePositions[first - 1], type(owner));
                    var enclosing = owner.enclosingType(); // Outer in Outer.Inner.X
                    for (var i = first - 2; i >= 0 && enclosing != null; i--, enclosing = enclosing.enclosingType()) {
                        put(names, reference.sourcePositions[i], type(enclosing));
                    }
                }
                if (reference.otherBindings != null) {
                    var receiver = reference.binding instanceof org.eclipse.jdt.internal.compiler.lookup.VariableBinding variable ? variable.type : null;
                    for (var i = 0; i < reference.otherBindings.length && first + 1 + i < reference.sourcePositions.length; i++) {
                        var other = reference.otherBindings[i];
                        put(names, reference.sourcePositions[first + 1 + i], field(other));
                        if (notFound(other)) EcjBindings.put(names, reference.sourcePositions[first + 1 + i], nothing(receiver, scope));
                        receiver = other == null ? null : other.type;
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
                if (allocation.type != null && !wrong((int) (nameOf(allocation.type) >>> 32), (int) nameOf(allocation.type))) {
                    put(constructors, nameOf(allocation.type), constructor(allocation.binding));
                } else if (allocation.type != null && allocation.resolvedType instanceof SourceTypeBinding made && made.isValidBinding()
                        && noneTakes(made, allocation.arguments == null ? 0 : allocation.arguments.length)) {
                    EcjBindings.put(constructors, nameOf(allocation.type), nothing(made, scope)); // a workspace type no constructor of which takes as many
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedAllocationExpression allocation, BlockScope scope) {
                if (allocation.anonymousType == null && allocation.type != null && !wrong((int) (nameOf(allocation.type) >>> 32), (int) nameOf(allocation.type))) {
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
                if (!wrong(reference.nameSourceStart, reference.sourceEnd)) {
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
                if (reference instanceof QualifiedTypeReference qualified && resolved instanceof ReferenceBinding type) { // Outer in Outer.Inner
                    var enclosing = type.enclosingType();
                    for (var i = qualified.sourcePositions.length - 2; i >= 0 && enclosing != null; i--, enclosing = enclosing.enclosingType()) {
                        put(types, qualified.sourcePositions[i], type(enclosing));
                    }
                }
                var at = nameOf(reference);
                var missing = false;
                for (var type : missingTypes) missing |= type[0] <= (int) at && type[1] >= (int) (at >>> 32);
                if (resolved instanceof ProblemReferenceBinding problem && problem.problemId() == ProblemReasons.NotFound
                        || resolved instanceof org.eclipse.jdt.internal.compiler.lookup.MissingTypeBinding || missing && !(resolved instanceof ReferenceBinding found && found.isValidBinding()
                        && !(found instanceof org.eclipse.jdt.internal.compiler.lookup.MissingTypeBinding))) { // what the compiler makes up to go on
                    EcjBindings.put(types, nameOf(reference), new Bound(Kind.NOTHING, null, null, List.of(), -1, null));
                }
            }
        }, unit.scope, false); // a unit with errors too: what binds nothing is where they are
        return new File(names, types, constructors, declared);
    }

    /** What a call or a new object does not apply to, or applies to more than one: what it is bound to is a guess. */
    private static final java.util.Set<Integer> APPLIES_NOT = java.util.Set.of(org.eclipse.jdt.core.compiler.IProblem.ParameterMismatch,
            org.eclipse.jdt.core.compiler.IProblem.UndefinedMethod, org.eclipse.jdt.core.compiler.IProblem.UndefinedConstructor,
            org.eclipse.jdt.core.compiler.IProblem.AmbiguousMethod, org.eclipse.jdt.core.compiler.IProblem.AmbiguousConstructor,
            org.eclipse.jdt.core.compiler.IProblem.ParameterizedMethodArgumentTypeMismatch,
            org.eclipse.jdt.core.compiler.IProblem.ParameterizedConstructorArgumentTypeMismatch,
            org.eclipse.jdt.core.compiler.IProblem.GenericMethodTypeArgumentMismatch,
            org.eclipse.jdt.core.compiler.IProblem.GenericConstructorTypeArgumentMismatch,
            org.eclipse.jdt.core.compiler.IProblem.IncorrectArityForParameterizedMethod,
            org.eclipse.jdt.core.compiler.IProblem.IncorrectArityForParameterizedConstructor);

    private static final java.lang.reflect.Field COPIES = copies();

    private static java.lang.reflect.Field copies() {
        try {
            var field = LambdaExpression.class.getDeclaredField("copiesPerTargetType");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException none) {
            return null;
        }
    }

    /** The copy of a lambda resolved for the type it has, if the compiler made copies. */
    private static LambdaExpression resolvedCopy(LambdaExpression lambda) {
        if (COPIES == null) return null;
        try {
            var copies = (Map<?, ?>) COPIES.get(lambda);
            if (copies == null || copies.isEmpty()) return null;
            var copy = lambda.resolvedType == null ? null : copies.get(lambda.resolvedType);
            return (LambdaExpression) (copy != null ? copy : copies.values().iterator().next());
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether nothing of that name was found. */
    private static boolean notFound(Binding binding) {
        return binding != null && !binding.isValidBinding() && binding.problemId() == ProblemReasons.NotFound;
    }

    /** What binds nothing, looked up in a type: the receiver's, else the one it is written in. */
    private static Bound nothing(TypeBinding receiver, BlockScope scope) {
        var in = receiver instanceof ReferenceBinding type ? type : scope == null ? null : scope.enclosingSourceType();
        return new Bound(Kind.NOTHING, in == null ? null : qualified(in), null, List.of(), -1, null);
    }

    /** Whether no constructor of a type takes as many arguments; with none declared, the one Java gives takes none. */
    private static boolean noneTakes(ReferenceBinding type, int passed) {
        var constructors = type.getMethods(org.eclipse.jdt.internal.compiler.lookup.TypeConstants.INIT);
        if (constructors == null || constructors.length == 0) return passed > 0;
        for (var constructor : constructors) {
            var takes = constructor.parameters.length;
            if (takes == passed || constructor.isVarargs() && passed >= takes - 1) return false;
        }
        return true;
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
        if (field instanceof org.eclipse.jdt.internal.compiler.lookup.ProblemFieldBinding problem && problem.problemId() == ProblemReasons.NotVisible
                && problem.closestMatch != null) {
            field = problem.closestMatch; // it is that one, which cannot be reached from here: an access to open, not nothing
        }
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
        if (type instanceof ProblemReferenceBinding problem && problem.problemId() == ProblemReasons.NotVisible
                && problem.closestMatch() instanceof ReferenceBinding seen) {
            type = seen; // as for a field: it is that one, not visible from here
        }
        if (!type.isValidBinding()) return null;
        var qualified = qualified(type);
        if (qualified == null) return null;
        var kind = type.isAnnotationType() ? "annotation" : type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
        return new Bound(Kind.TYPE, qualified, kind, List.of(), -1, new String(((ReferenceBinding) type.erasure()).qualifiedPackageName()));
    }

    /** A method's parameters' types; a variable arity one as its elements' type, as the program has always keyed it. */
    private static List<String> parameters(MethodBinding method) {
        var described = new ArrayList<String>();
        for (var i = 0; i < method.parameters.length; i++) {
            var parameter = method.parameters[i];
            if (method.isVarargs() && i == method.parameters.length - 1 && parameter instanceof ArrayBinding array) parameter = array.elementsType();
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
