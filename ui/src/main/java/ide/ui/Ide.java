package ide.ui;

import ide.core.Capability;
import ide.core.Json;
import ide.session.Workspace;

import java.util.List;
import java.util.Map;

/** The IDE as the window sees it: capabilities by name, called with arguments, answered as plain values. */
final class Ide {

    private final Workspace workspace;

    Ide(Workspace workspace) {
        this.workspace = workspace;
    }

    Capability capability(String id) {
        return workspace.capabilities().get(id);
    }

    /** Whether the IDE has a capability by that name. */
    boolean has(String id) {
        return workspace.capabilities().ids().contains(id);
    }

    /** What a capability answers, read from its JSON: a map, a list, text, a number, a truth, or nothing. */
    Object call(String capability, Map<String, ?> args) {
        return Json.read(workspace.capabilities().invoke(capability, args));
    }

    List<String> list(String capability, Map<String, ?> args) {
        return ((List<?>) call(capability, args)).stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> records(String capability, Map<String, ?> args) {
        return (List<Map<String, Object>>) call(capability, args);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> record(String capability, Map<String, ?> args) {
        return (Map<String, Object>) call(capability, args);
    }

    String text(String capability, Map<String, ?> args) {
        return String.valueOf(call(capability, args));
    }
}
