package ide.program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a method does, in no language: statements over elements, which each projection writes in its own. As text it
 * is one statement per line (or after a {@code ;}), each element named by id as {@code #{id}} ({@link #name}):
 * <pre>
 * return #{field:2}                              answers the value of a field or of a parameter
 * #{field:2} = #{param:7}                        gives a field a value
 * return new #{constructor:3}(#{param:7}, ...)   answers a new object
 * return default                                 answers what its type starts with; nothing, if it answers nothing
 * return #{field:2}.#{method:4}(#{param:7})      answers what a call answers: on what a field or a parameter holds,
 * return #{method:4}(#{param:7})                 or on the object itself; a method that answers nothing just calls
 * ...                                            what the method does now, as it is: first, and what follows is added
 * #{constructor:3}(#{param:7})                   a constructor's object made first by another constructor, of its
 *                                                type or of its superclass: first
 * </pre>
 * A body is the body of one method: its parameters are values the body has, not uses between elements. Like
 * {@link Fact}, it grows only when a template needs to say something it cannot.
 */
public record Body(List<Statement> statements) {

    public Body {
        statements = List.copyOf(statements);
    }

    public sealed interface Statement {
    }

    /** What the method does now, kept as it is written: what the body says after it is added at its end. */
    public record Kept() implements Statement {
    }

    /** The object is made first by {@code constructor}, given {@code arguments}: only a constructor's body says it, first. */
    public record Construct(ElementId constructor, List<Expression> arguments) implements Statement {
        public Construct {
            arguments = List.copyOf(arguments);
        }
    }

    /** Answers {@code value}. */
    public record Return(Expression value) implements Statement {
    }

    /** Gives {@code field} the value {@code value}. */
    public record Assign(ElementId field, Expression value) implements Statement {
    }

    public sealed interface Expression {
    }

    /** The value of a field. */
    public record Field(ElementId field) implements Expression {
    }

    /** The value of one of the method's parameters. */
    public record Parameter(ElementId parameter) implements Expression {
    }

    /** What the method's type starts with, as a field of that type does: what a method answers that knows nothing yet. */
    public record Default() implements Expression {
    }

    /** A call of {@code method} with {@code arguments}, on what {@code receiver} holds (a field or a parameter), or on the object itself if it is null. */
    public record Call(Expression receiver, ElementId method, List<Expression> arguments) implements Expression {
        public Call {
            arguments = List.copyOf(arguments);
        }
    }

    /** A new object, made by {@code constructor} with {@code arguments}. */
    public record New(ElementId constructor, List<Expression> arguments) implements Expression {
        public New {
            arguments = List.copyOf(arguments);
        }
    }

    /** What a body does with an element outside its method. */
    public record Use(ElementId element, Mode mode) {
    }

    /** How a body names an element. */
    public static String name(ElementId element) {
        return "#{" + element.value() + "}";
    }

    /**
     * The body of {@code method} written in {@code text}. Each element it names must be in {@code program} and be what
     * its place takes: a field or one of the method's parameters as a value, a field to assign, a constructor to make
     * an object with.
     */
    public static Body parse(String text, Program program, ElementId method) {
        var statements = new ArrayList<Statement>();
        for (var line : text.lines().flatMap(line -> java.util.Arrays.stream(line.split(";"))).map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            if (line.equals("...")) {
                if (!statements.isEmpty()) throw new IllegalArgumentException("body: '...' comes first, in: " + text);
                statements.add(new Kept());
            } else {
                var statement = new Parser(line, program, method).statement();
                if (statement instanceof Construct && !statements.isEmpty()) {
                    throw new IllegalArgumentException("body: a constructor called comes first, in: " + text);
                }
                statements.add(statement);
            }
        }
        return new Body(statements);
    }

    /** Whether it keeps what the method does now and adds to it, instead of taking its place. */
    public boolean keeps() {
        return !statements.isEmpty() && statements.getFirst() instanceof Kept;
    }

    /**
     * A value said in no language, as a field starts with it ({@code #{method:4}()}): an expression of a body, read
     * from inside {@code site}.
     */
    public static Expression value(String text, Program program, ElementId site) {
        var statement = new Parser("return " + text.strip(), program, site).statement();
        return ((Return) statement).value();
    }

    /** Whether a text says something in no language: it names elements as a body does. */
    public static boolean names(String text) {
        return text.contains("#{");
    }

    /** The uses a value makes, in the order it names them. */
    public static List<Use> uses(Expression value) {
        var uses = new ArrayList<Use>();
        uses(value, uses);
        return uses;
    }

    /** The uses the body makes of elements outside its method, in the order it names them. */
    public List<Use> uses() {
        var uses = new ArrayList<Use>();
        for (var statement : statements) {
            switch (statement) {
                case Kept k -> {
                    // the uses the method already makes
                }
                case Construct c -> {
                    uses.add(new Use(c.constructor(), Mode.CALL));
                    c.arguments().forEach(argument -> uses(argument, uses));
                }
                case Return r -> uses(r.value(), uses);
                case Assign a -> {
                    uses.add(new Use(a.field(), Mode.WRITE));
                    uses(a.value(), uses);
                }
            }
        }
        return uses;
    }

    private static void uses(Expression expression, List<Use> uses) {
        switch (expression) {
            case Field f -> uses.add(new Use(f.field(), Mode.READ));
            case Parameter p -> {
                // a value of the body itself
            }
            case Default d -> {
                // no element at all
            }
            case Call call -> {
                if (call.receiver() != null) uses(call.receiver(), uses);
                uses.add(new Use(call.method(), Mode.CALL));
                call.arguments().forEach(argument -> uses(argument, uses));
            }
            case New n -> {
                uses.add(new Use(n.constructor(), Mode.NEW));
                n.arguments().forEach(argument -> uses(argument, uses));
            }
        }
    }

    /** One statement, read left to right. */
    private static final class Parser {

        private final String text;
        private final Program program;
        private final ElementId method;
        private int at;

        Parser(String text, Program program, ElementId method) {
            this.text = text;
            this.program = program;
            this.method = method;
        }

        Statement statement() {
            Statement statement;
            if (keyword("return")) {
                statement = new Return(expression());
            } else {
                var element = element(Kind.FIELD, Kind.CONSTRUCTOR);
                if (program.element(element).kind() == Kind.CONSTRUCTOR) {
                    if (program.element(method).kind() != Kind.CONSTRUCTOR) {
                        throw new IllegalArgumentException("body: only a constructor has its object made by " + element + ", in: " + text);
                    }
                    statement = new Construct(element, ((Call) call(null, element)).arguments());
                } else {
                    expect('=');
                    statement = new Assign(element, expression());
                }
            }
            spaces();
            if (at < text.length()) throw error("the end");
            return statement;
        }

        private Expression expression() {
            spaces();
            if (text.startsWith("default", at) && (at + 7 == text.length() || !Character.isJavaIdentifierPart(text.charAt(at + 7)))) {
                at += 7;
                return new Default();
            }
            if (keyword("new")) {
                var constructor = element(Kind.CONSTRUCTOR);
                expect('(');
                var arguments = new ArrayList<Expression>();
                spaces();
                if (!next(')')) {
                    do arguments.add(expression());
                    while (next(','));
                }
                expect(')');
                return new New(constructor, arguments);
            }
            var element = element(Kind.FIELD, Kind.PARAM, Kind.METHOD);
            if (program.element(element).kind() == Kind.METHOD) return call(null, element);
            Expression value;
            if (program.element(element).kind() == Kind.FIELD) {
                value = new Field(element);
            } else {
                if (!method.equals(program.element(element).parent())) {
                    throw new IllegalArgumentException("body: " + element + " is not a parameter of " + method + ", in: " + text);
                }
                value = new Parameter(element);
            }
            return next('.') ? call(value, element(Kind.METHOD)) : value;
        }

        /** The arguments of a call of {@code called}, after its name. */
        private Expression call(Expression receiver, ElementId called) {
            expect('(');
            var arguments = new ArrayList<Expression>();
            spaces();
            if (!next(')')) {
                do arguments.add(expression());
                while (next(','));
                expect(')');
            }
            return new Call(receiver, called, arguments);
        }

        private ElementId element(Kind... kinds) {
            spaces();
            var close = text.indexOf('}', at);
            if (!text.startsWith("#{", at) || close < 0) throw error("an element, #{id}");
            var id = new ElementId(text.substring(at + 2, close));
            at = close + 1;
            if (!program.contains(id)) throw new IllegalArgumentException("body: no element " + id + ", in: " + text);
            var kind = program.element(id).kind();
            if (!Arrays.asList(kinds).contains(kind)) {
                throw new IllegalArgumentException("body: " + id + " is a " + kind + " where " + Arrays.toString(kinds)
                        + " goes, in: " + text);
            }
            return id;
        }

        /** The word, followed by a space. */
        private boolean keyword(String word) {
            spaces();
            if (!text.startsWith(word + " ", at)) return false;
            at += word.length();
            return true;
        }

        private boolean next(char c) {
            spaces();
            if (at >= text.length() || text.charAt(at) != c) return false;
            at++;
            return true;
        }

        private void expect(char c) {
            if (!next(c)) throw error("'" + c + "'");
        }

        private void spaces() {
            while (at < text.length() && text.charAt(at) == ' ') at++;
        }

        private IllegalArgumentException error(String expected) {
            return new IllegalArgumentException("body: expected " + expected + " at " + at + ", in: " + text);
        }
    }
}
