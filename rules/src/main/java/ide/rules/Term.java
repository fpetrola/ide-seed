package ide.rules;

/** What an atom says of each place: a variable, which an answer binds, or a constant. */
public sealed interface Term {

    /** A variable; one whose name starts with {@code _} is anonymous: any value, never asked for. */
    record Variable(String name) implements Term {

        public boolean anonymous() {
            return name.startsWith("_");
        }

        @Override
        public String toString() {
            return name;
        }
    }

    record Constant(String value) implements Term {

        @Override
        public String toString() {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    static Variable variable(String name) {
        return new Variable(name);
    }

    static Constant constant(String value) {
        return new Constant(value);
    }
}
