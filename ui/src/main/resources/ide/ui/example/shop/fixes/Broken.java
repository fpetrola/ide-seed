package shop.fixes;

/**
 * Errors on purpose, for the quick fixes (Ctrl+1 on each line with a red mark; Ctrl+. goes to the next one):
 *  - List: not imported; the fix imports java.util.List.
 *  - Vault: in another package, and not public; the fix opens it, then imports it (two steps, deduced backward).
 *  - count: no such field; the fix creates it, asking its type.
 *  - itmes, sizee(): misspelled; the fixes change them to the field and the method they almost name.
 */
public class Broken {
    List<String> names;
    Vault vault;

    int total() {
        return count * 2;
    }

    int items;

    int all() {
        return itmes + sizee();
    }

    int size() {
        return 0;
    }
}
