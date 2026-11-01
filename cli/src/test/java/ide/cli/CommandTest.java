package ide.cli;

import ide.core.Capability;
import ide.core.CapabilityRegistry;
import ide.core.Param;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Words become a capability and its arguments, by the capability's schema: nothing of the IDE is known here. */
class CommandTest {

    private static final CapabilityRegistry REGISTRY = new CapabilityRegistry()
            .register(Capability.of("extract", "Extracts", List.of(Param.of("type", "element", "CLASS"),
                    Param.of("name", "text"), Param.of("fields", "elements", "FIELD"), Param.of("deep", "bool").optional()), args -> args))
            .register(Capability.of("preview", "Previews", List.of(Param.of("refactoring", "text"), Param.of("args", "text")), args -> args))
            .register(Capability.of("fix", "Fixes", List.of(Param.of("title", "text"), Param.of("answers", "text").optional()), args -> args));

    @Test
    void wordsBindTheParametersInOrderOrByName() {
        var command = Command.of(List.of("extract", "a.B", "fields=a.B#x, a.B#y", "name=Base"), REGISTRY);

        assertThat(command.capability()).isEqualTo("extract");
        assertThat(command.args()).isEqualTo(Map.of("type", "a.B", "name", "Base", "fields", List.of("a.B#x", "a.B#y")));
    }

    @Test
    void aTruthIsATruthAndAParameterOfSeveralEntriesTakesThemByName() {
        assertThat(Command.of(List.of("extract", "a.B", "Base", "a.B#x", "deep=false"), REGISTRY).args().get("deep")).isEqualTo(false);
        assertThat(Command.of(List.of("preview", "rename", "args.element=a.P#x", "args.name=y"), REGISTRY).args())
                .isEqualTo(Map.of("refactoring", "rename", "args", Map.of("element", "a.P#x", "name", "y")));
        assertThat(Command.of(List.of("fix", "create field count", "answers.type=short"), REGISTRY).args())
                .isEqualTo(Map.of("title", "create field count", "answers", Map.of("type", "short")));
    }

    @Test
    void aLineIsSplitAtSpacesOutsideQuotes() {
        assertThat(Command.words("fix a/U.java 5 \"create field count\"  type=int")).containsExactly("fix", "a/U.java", "5",
                "create field count", "type=int");
    }

    @Test
    void whatTheSchemaDoesNotTakeIsSaid() {
        assertThatThrownBy(() -> Command.of(List.of("nope"), REGISTRY)).hasMessageContaining("unknown capability: nope");
        assertThatThrownBy(() -> Command.of(List.of("preview", "a", "b", "c"), REGISTRY)).hasMessageContaining("too many arguments for preview");
    }
}
