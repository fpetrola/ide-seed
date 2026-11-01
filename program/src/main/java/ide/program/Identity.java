package ide.program;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Who is who: address → stable id. Reading never decides identity: a known address keeps its id, a new one gets a
 * fresh id, and only the primitives move ids when they change an address (rebind a type, rename, move, re-sign).
 * Renders addresses for humans as {@code a.B#m(int,Money)/x}.
 */
public final class Identity {

    /** Everything needed to come back to this identity. */
    public record State(Map<Address, ElementId> ids, int next) {
    }

    private static final Pattern TOKEN = Pattern.compile("[\\w$.:]+");

    private final Map<Address, ElementId> ids = new HashMap<>();
    private final Map<ElementId, Address> addresses = new HashMap<>();
    private final Map<String, ElementId> types = new HashMap<>();
    private int next;

    /** The id at {@code address}, created if the address is new. */
    public ElementId of(Address address, Kind kind) {
        var id = ids.get(address);
        if (id == null) {
            id = new ElementId(kind.prefix() + ":" + next++);
            put(address, id);
        }
        return id;
    }

    /** The id at {@code address}, or null. */
    public ElementId get(Address address) {
        return ids.get(address);
    }

    public boolean has(Address address) {
        return ids.containsKey(address);
    }

    /** The current address of an id, or null. */
    public Address address(ElementId id) {
        return addresses.get(id);
    }

    /** Qualified name → id of every type. */
    public Map<String, ElementId> types() {
        return Collections.unmodifiableMap(types);
    }

    /** A copy, safe to iterate while changing the identity. */
    public List<Address> addresses() {
        return List.copyOf(ids.keySet());
    }

    /** A type (and the types nested in it) is now known by another qualified name; its members keep their addresses. */
    public void rebindType(String oldQualified, String newQualified) {
        if (oldQualified.equals(newQualified)) return;
        for (var address : addresses()) {
            if (!(address instanceof Address.Type type)) continue;
            if (type.qualified().equals(oldQualified)) rebind(address, new Address.Type(newQualified));
            else if (type.qualified().startsWith(oldQualified + ".")) {
                rebind(address, new Address.Type(newQualified + type.qualified().substring(oldQualified.length())));
            }
        }
    }

    public void rename(ElementId id, String name) {
        rebind(address(id), address(id).withName(name));
    }

    public void move(ElementId id, ElementId owner) {
        rebind(address(id), address(id).withOwner(owner));
    }

    /**
     * The locals of {@code callable} are these, in the order they are written, with these names: each is the next of
     * its name, and all take their places at once, so that two may trade them. A local may come from another callable;
     * one that is not here keeps its address.
     */
    public void placeLocals(ElementId callable, SequencedMap<ElementId, String> written) {
        var seen = new HashMap<String, Integer>();
        var placed = new HashMap<ElementId, Address>();
        written.forEach((local, name) -> placed.put(local, new Address.Local(callable, name, seen.merge(name, 1, Integer::sum) - 1)));
        place(placed);
    }

    /**
     * The statements of {@code callable} are these, in order: each takes its place, all at once. A null is a statement
     * no id is known for yet, whose place stays free for the reading to give one. A statement may come from another
     * callable; one that is not here keeps its address.
     */
    public void placeStatements(ElementId callable, List<ElementId> written) {
        var placed = new HashMap<ElementId, Address>();
        for (var i = 0; i < written.size(); i++) if (written.get(i) != null) placed.put(written.get(i), new Address.Statement(callable, i));
        place(placed);
    }

    /** Each id at its address, all at once, so that two may trade places; no other element may hold one of them. */
    private void place(Map<ElementId, Address> placed) {
        for (var address : placed.values()) {
            var holder = ids.get(address);
            if (holder != null && !placed.containsKey(holder)) throw new IllegalStateException(render(address) + " already exists");
        }
        placed.keySet().forEach(this::remove);
        placed.forEach((element, address) -> put(address, element));
    }

    /** A callable's parameter list changed: same owner and name, new keys. */
    public void resign(ElementId id, List<String> keys) {
        var callable = (Address.Callable) address(id);
        rebind(callable, new Address.Callable(callable.owner(), callable.name(), keys));
    }

    /**
     * The element read anew as {@code fresh} is the one known as {@code old}: {@code old} takes its address (a type
     * takes its nested types along), and {@code fresh}, with whatever was read under it, is forgotten.
     */
    public void transfer(ElementId fresh, ElementId old) {
        var to = address(fresh);
        var from = address(old);
        if (to == null || from == null) return;
        forget(fresh);
        if (from instanceof Address.Type type) rebindType(type.qualified(), ((Address.Type) to).qualified());
        else rebind(from, to);
    }

    /** Forgets an element and everything addressed under it: members, parameters, nested types. */
    public void forget(ElementId id) {
        var gone = new HashSet<ElementId>();
        gone.add(id);
        var prefixes = new ArrayList<String>();
        if (address(id) instanceof Address.Type type) prefixes.add(type.qualified() + ".");
        var more = true;
        while (more) {
            more = false;
            for (var address : addresses()) {
                var owned = ids.get(address);
                if (gone.contains(owned)) continue;
                var nested = address instanceof Address.Type t && prefixes.stream().anyMatch(p -> t.qualified().startsWith(p));
                if (nested || (address.owner() != null && gone.contains(address.owner()))) {
                    gone.add(owned);
                    if (address instanceof Address.Type t) prefixes.add(t.qualified() + ".");
                    more = true;
                }
            }
        }
        gone.forEach(this::remove);
    }

    /**
     * The address as humans read it: {@code a}, {@code a.B}, {@code a.B#x}, {@code a.B#m(int,Money)}, {@code a.B#m(int)/p},
     * and the first statement of a body {@code a.B#m(int)[1]}.
     */
    public String render(Address address) {
        return switch (address) {
            case Address.Package p -> p.name();
            case Address.Type t -> t.qualified();
            case Address.Field f -> render(address(f.owner())) + "#" + f.name();
            case Address.Callable c -> render(address(c.owner())) + "#" + c.name()
                    + c.keys().stream().map(this::renderKey).collect(Collectors.joining(",", "(", ")"));
            case Address.Param p -> render(address(p.owner())) + "/" + p.name();
            case Address.Local l -> render(address(l.owner())) + "/" + l.name() + (l.ordinal() == 0 ? "" : "'" + (l.ordinal() + 1));
            case Address.Statement s -> render(address(s.owner())) + "[" + (s.index() + 1) + "]";
        };
    }

    /** A parameter type key with every id written as the simple name of its type. */
    private String renderKey(String key) {
        return TOKEN.matcher(key).replaceAll(m -> {
            var named = addresses.get(new ElementId(m.group()));
            return Matcher.quoteReplacement(named instanceof Address.Type t ? Address.simpleName(t.qualified()) : m.group());
        });
    }

    public State save() {
        return new State(Map.copyOf(ids), next);
    }

    public void restore(State state) {
        ids.clear();
        addresses.clear();
        types.clear();
        state.ids().forEach(this::put);
        next = state.next();
    }

    private void rebind(Address from, Address to) {
        if (from.equals(to)) return;
        if (ids.containsKey(to)) throw new IllegalStateException(render(to) + " already exists");
        var id = ids.get(from);
        remove(id);
        put(to, id);
    }

    private void put(Address address, ElementId id) {
        ids.put(address, id);
        addresses.put(id, address);
        if (address instanceof Address.Type type) types.put(type.qualified(), id);
    }

    private void remove(ElementId id) {
        var address = addresses.remove(id);
        if (address == null) return;
        ids.remove(address);
        if (address instanceof Address.Type type) types.remove(type.qualified());
    }
}
