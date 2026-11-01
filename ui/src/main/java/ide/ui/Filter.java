package ide.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * What a quick pick shows for what people type: the items that contain it, or, when it has capitals, the ones whose
 * name's words begin with its words ({@code OB} or {@code OrdB} for {@code OrderBilling}).
 */
final class Filter {

    private Filter() {
    }

    static List<String> matching(String query, List<String> items) {
        if (query.isBlank()) return items;
        var lower = query.toLowerCase();
        return items.stream().filter(item -> item.toLowerCase().contains(lower) || initials(query, name(item))).toList();
    }

    /** The name in an address: after its last '#' or '.', before its parameters. */
    private static String name(String address) {
        var paren = address.indexOf('(');
        var end = paren < 0 ? address.length() : paren;
        var start = Math.max(address.lastIndexOf('#', end - 1), address.lastIndexOf('.', end - 1)) + 1;
        return address.substring(start, end);
    }

    private static boolean initials(String query, String name) {
        var asked = words(query);
        var named = words(name);
        if (asked.size() < 2 || asked.size() > named.size()) return false;
        for (var i = 0; i < asked.size(); i++) if (!named.get(i).startsWith(asked.get(i))) return false;
        return true;
    }

    /** {@code OrderBilling} is {@code Order}, {@code Billing}: a word starts at each capital. */
    private static List<String> words(String text) {
        var words = new ArrayList<String>();
        var start = 0;
        for (var i = 1; i <= text.length(); i++) {
            if (i == text.length() || Character.isUpperCase(text.charAt(i))) {
                words.add(text.substring(start, i));
                start = i;
            }
        }
        return words;
    }
}
