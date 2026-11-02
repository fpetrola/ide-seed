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
 * add #{local:5} to #{local:6}                   puts a value at the end of a list
 * each #{local:8} in #{local:6}                  does, for each value of a list held in a local of its own, what
 *                                                its block will hold: made empty, the local declared by it
 * list of #{class:1}                             a new list, empty, of values of a type (a value; also a local's type)
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

    /** Gives {@code local}, a local of the method, the value {@code value}. */
    public record Let(ElementId local, Expression value) implements Statement {
    }

    /** Puts {@code value} at the end of {@code list}. */
    public record Add(Expression value, Expression list) implements Statement {
    }

    /** Does, for each value of {@code list} held in {@code local}, what its block holds: made empty, declaring the local. */
    public record Each(ElementId local, Expression list) implements Statement {
    }

    public sealed interface Expression {
    }

    /** The value of a field. */
    public record Field(ElementId field) implements Expression {
    }

    /** The value of one of the method's parameters, or of one of its locals: a read of it, from the method. */
    public record Parameter(ElementId parameter) implements Expression {
    }

    /** A new list, empty, of values of {@code type}. */
    public record NewList(ElementId type) implements Expression {
    }

    /** The type of the elements of a list type said in no language ({@code list of #{class:1}}), or null if it is no such text. */
    public static ElementId listOf(String text) {
        var matcher = java.util.regex.Pattern.compile("list of #\\{([^}]+)}").matcher(text.strip());
        return matcher.matches() ? new ElementId(matcher.group(1)) : null;
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

    /** What a body does with an element outside its method, and on what: a call on a value is made on another object. */
    public record Use(ElementId element, Mode mode, Reference.Receiver receiver) {

        public Use(ElementId element, Mode mode) {
            this(element, mode, Reference.Receiver.of(mode));
        }
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
            var statement = new Parser(line, program, method).statement();
            if (statement instanceof Construct && !statements.isEmpty()) {
                throw new IllegalArgumentException("body: a constructor called comes first, in: " + text);
            }
            statements.add(statement);
        }
        return new Body(statements);
    }

    /**
     * A value said in no language, as a field starts with it ({@code #{method:4}()}): an expression of a body, read
     * from inside {@code site}.
     */
    public static Expression value(String text, Program program, ElementId site) {
        var statement = new Parser("return " + text.strip(), program, site).statement();
        return ((Return) statement).value();
    }

    /** Whether a text says something in no language: it names elements as a body does, or is the type's default. */
    public static boolean names(String text) {
        return text.contains("#{") || text.strip().equals("default");
    }

    /** The body as it is said: a statement a line, each element named by id. */
    public String text() {
        return String.join("\n", statements.stream().map(Body::text).toList());
    }

    /** A statement as it is said. */
    public static String text(Statement statement) {
        return switch (statement) {
            case Construct c -> name(c.constructor()) + "(" + text(c.arguments()) + ")";
            case Return r -> "return " + text(r.value());
            case Assign a -> name(a.field()) + " = " + text(a.value());
            case Let l -> name(l.local()) + " = " + text(l.value());
            case Add a -> "add " + text(a.value()) + " to " + text(a.list());
            case Each e -> "each " + name(e.local()) + " in " + text(e.list());
        };
    }

    /** A value as it is said. */
    public static String text(Expression value) {
        return switch (value) {
            case Field f -> name(f.field());
            case Parameter p -> name(p.parameter());
            case Default d -> "default";
            case Call call -> (call.receiver() == null ? "" : text(call.receiver()) + ".") + name(call.method()) + "(" + text(call.arguments()) + ")";
            case New n -> "new " + name(n.constructor()) + "(" + text(n.arguments()) + ")";
            case NewList l -> "list of " + name(l.type());
        };
    }

    private static String text(List<Expression> values) {
        return String.join(", ", values.stream().map(Body::text).toList());
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
        for (var statement : statements) uses.addAll(uses(statement));
        return uses;
    }

    /** The uses one statement makes, in the order it names them. */
    public static List<Use> uses(Statement statement) {
        var uses = new ArrayList<Use>();
        switch (statement) {
            case Construct c -> {
                uses.add(new Use(c.constructor(), Mode.CALL));
                c.arguments().forEach(argument -> uses(argument, uses));
            }
            case Return r -> uses(r.value(), uses);
            case Assign a -> {
                uses.add(new Use(a.field(), Mode.WRITE));
                uses(a.value(), uses);
            }
            case Let l -> {
                uses.add(new Use(l.local(), Mode.WRITE, Reference.Receiver.NONE)); // written from the method, on nothing
                uses(l.value(), uses);
            }
            case Add a -> {
                uses(a.list(), uses);
                uses(a.value(), uses);
            }
            case Each e -> uses(e.list(), uses);
        }
        return uses;
    }

    private static void uses(Expression expression, List<Use> uses) {
        switch (expression) {
            case Field f -> uses.add(new Use(f.field(), Mode.READ));
            case Parameter p -> uses.add(new Use(p.parameter(), Mode.READ, Reference.Receiver.NONE)); // read from the method, on nothing
            case Default d -> {
                // no element at all
            }
            case Call call -> {
                if (call.receiver() != null) uses(call.receiver(), uses);
                uses.add(new Use(call.method(), Mode.CALL, call.receiver() == null ? Reference.Receiver.SELF : Reference.Receiver.OTHER));
                call.arguments().forEach(argument -> uses(argument, uses));
            }
            case New n -> {
                uses.add(new Use(n.constructor(), Mode.NEW));
                n.arguments().forEach(argument -> uses(argument, uses));
            }
            case NewList l -> {
                // the list's type is no element; what its values are is the local's declared type
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
            } else if (keyword("add")) {
                var value = expression();
                if (!keyword("to")) throw error("'to'");
                statement = new Add(value, expression());
            } else if (keyword("each")) {
                var local = element(Kind.LOCAL);
                if (!method.equals(program.element(local).parent())) {
                    throw new IllegalArgumentException("body: " + local + " is not a local of " + method + ", in: " + text);
                }
                if (!keyword("in")) throw error("'in'");
                statement = new Each(local, expression());
            } else {
                var element = element(Kind.FIELD, Kind.CONSTRUCTOR, Kind.LOCAL);
                if (program.element(element).kind() == Kind.LOCAL && !method.equals(program.element(element).parent())) {
                    throw new IllegalArgumentException("body: " + element + " is not a local of " + method + ", in: " + text);
                }
                if (program.element(element).kind() == Kind.CONSTRUCTOR) {
                    if (program.element(method).kind() != Kind.CONSTRUCTOR) {
                        throw new IllegalArgumentException("body: only a constructor has its object made by " + element + ", in: " + text);
                    }
                    statement = new Construct(element, ((Call) call(null, element)).arguments());
                } else if (program.element(element).kind() == Kind.LOCAL) {
                    expect('=');
                    statement = new Let(element, expression());
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
            if (keyword("list")) {
                if (!keyword("of")) throw error("'of'");
                return new NewList(element(Kind.CLASS, Kind.INTERFACE, Kind.ENUM));
            }
            if (keyword("new")) {
                var constructor = element(Kind.CONSTRUCTOR);
                expect('(');
                var arguments = new ArrayList<Expression>();
                spaces();
                if (!next(')')) {
                    do arguments.add(expression());
                    while (next(','));
                    expect(')');
                }
                return new New(constructor, arguments);
            }
            var element = element(Kind.FIELD, Kind.PARAM, Kind.LOCAL, Kind.METHOD);
            if (program.element(element).kind() == Kind.METHOD) return call(null, element);
            Expression value;
            if (program.element(element).kind() == Kind.FIELD) {
                value = new Field(element);
            } else {
                if (!method.equals(program.element(element).parent())) {
                    throw new IllegalArgumentException("body: " + element + " is not a parameter or a local of " + method + ", in: " + text);
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
