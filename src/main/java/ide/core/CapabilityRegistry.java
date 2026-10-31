package ide.core;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** The capabilities adapters (CLI, UI, MCP) project; adapters only translate, never decide. */
public final class CapabilityRegistry {

    private final Map<String, Capability> capabilities = new TreeMap<>();

    public CapabilityRegistry register(Capability capability) {
        capabilities.put(capability.id(), capability);
        return this;
    }

    public List<String> ids() {
        return List.copyOf(capabilities.keySet());
    }

    public Capability get(String id) {
        var capability = capabilities.get(id);
        if (capability == null) throw new IllegalArgumentException("unknown capability: " + id);
        return capability;
    }

    public String invoke(String id, Map<String, ?> args) {
        return get(id).invoke(args);
    }

    /** One line: {@code id param:type(kinds)... — description}. */
    public String help(String id) {
        var capability = get(id);
        var params = capability.params().stream().map(p -> p.name() + ":" + p.type()
                + (p.kinds().isEmpty() ? "" : "(" + String.join(",", p.kinds()) + ")")
                + (p.required() ? "" : "?")).collect(Collectors.joining(" "));
        return capability.id() + (params.isEmpty() ? "" : " " + params) + " — " + capability.description();
    }
}
