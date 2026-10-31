package ide.core;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Something the IDE can do: a schema (id, description, params) and an invocation that answers JSON. */
public interface Capability {

    String id();

    String description();

    List<Param> params();

    String invoke(Map<String, ?> args);

    static Capability of(String id, String description, List<Param> params, Function<Map<String, ?>, Object> body) {
        return new Capability() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public List<Param> params() {
                return params;
            }

            @Override
            public String invoke(Map<String, ?> args) {
                return Json.write(body.apply(args));
            }
        };
    }
}
