package ide.program;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a method does, in no language: statements over elements, which each projection writes in its own. As text it
 * is one statement per line, each element named by id as {@code #{id}} ({@link #name}):
 * <pre>
 * return #{field:2}                              answers the value of a field or of a parameter
 * #{field:2} = #{param:7}                        gives a field a value
 * return new #{constructor:3}(#{param:7}, ...)   answers a new object
 * return default                                 answers what its type starts with; nothing, if it answers nothing
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
        for (var line : text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            statements.add(new Parser(line, program, method).statement());
        }
        return new Body(statements);
    }

    /** The uses the body makes of elements outside its method, in the order it names them. */
    public List<Use> uses() {
        var uses = new ArrayList<Use>();
        for (var statement : statements) {
            switch (statement) {
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
                var field = element(Kind.FIELD);
                expect('=');
                statement = new Assign(field, expression());
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
            var element = element(Kind.FIELD, Kind.PARAM);
            if (program.element(element).kind() == Kind.FIELD) return new Field(element);
            if (!method.equals(program.element(element).parent())) {
                throw new IllegalArgumentException("body: " + element + " is not a parameter of " + method + ", in: " + text);
            }
            return new Parameter(element);
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
