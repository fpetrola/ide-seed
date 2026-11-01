package shop.fixes;

/**
 * Errors on purpose, for the quick fixes (Ctrl+1 on each line with a red mark; Ctrl+. goes to the next one):
 *  - List: not imported; the fix imports java.util.List.
 *  - Vault: in another package, and not public; the fix opens it, then imports it (two steps, deduced backward).
 *  - count: no such field; the fix creates it, asking its type.
 *  - itmes, sizee(): misspelled; the fixes change them to the field and the method they almost name.
 *  - sum(items, 2): no such method; the fix creates it, taking what the call passes and answering a double.
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

    double average() {
        return sum(items, 2);
    }
}
