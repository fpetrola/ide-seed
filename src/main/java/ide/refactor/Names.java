package ide.refactor;

final class Names {

    private Names() {
    }

    static String cap(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
