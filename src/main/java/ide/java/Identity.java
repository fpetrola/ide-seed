package ide.java;

import ide.graph.ElementId;
import ide.graph.Kind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is who: address ({@code a.B}, {@code a.B#m(int)}, {@code a.B#m(int)/x}) → stable id. Parsing never decides
 * identity: an address keeps its id, a new one gets a fresh id, and primitives transfer ids when addresses change.
 */
final class Identity {

    /** Everything needed to come back to this identity. */
    record State(Map<String, ElementId> ids, int next) {
    }

    private Map<String, ElementId> ids = new HashMap<>();
    private int next;

    /** The id at {@code address}, created if the address is new. */
    ElementId of(String address, Kind kind) {
        return ids.computeIfAbsent(address, a -> new ElementId(kind.prefix() + ":" + next++));
    }

    ElementId get(String address) {
        return ids.get(address);
    }

    boolean has(String address) {
        return ids.containsKey(address);
    }

    void forget(String address) {
        ids.remove(address);
    }

    /** A copy, safe to iterate while transferring or forgetting. */
    List<String> addresses() {
        return List.copyOf(ids.keySet());
    }

    /** Moves the identity at {@code oldAddress}, and that of everything addressed under it, to {@code newAddress}. */
    void transfer(String oldAddress, String newAddress) {
        if (oldAddress.equals(newAddress)) return;
        for (var address : addresses()) {
            if (address.equals(oldAddress)) {
                ids.put(newAddress, ids.remove(address));
            } else if (address.startsWith(oldAddress) && "#/.".indexOf(address.charAt(oldAddress.length())) >= 0) {
                ids.put(newAddress + address.substring(oldAddress.length()), ids.remove(address));
            }
        }
    }

    State save() {
        return new State(Map.copyOf(ids), next);
    }

    void restore(State state) {
        ids = new HashMap<>(state.ids());
        next = state.next();
    }
}
