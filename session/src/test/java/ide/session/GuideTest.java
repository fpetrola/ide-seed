package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** How an agent works on the IDE, said by the IDE itself: every tool it names is one the IDE has. */
class GuideTest {

    @Test
    void theGuideNamesOnlyCapabilitiesTheIdeHas() {
        var workspace = Workspace.of(Map.of("a/P.java", "package a;\n\npublic class P {\n}\n"));
        var guide = String.valueOf(Json.read(workspace.capabilities().invoke("guide", Map.of())));

        var named = Pattern.compile("`([a-z-]+)`").matcher(guide).results().map(m -> m.group(1)).toList();

        assertThat(named).contains("plan-new", "task-update", "task-link", "steering", "looking", "point");
        assertThat(workspace.capabilities().ids()).containsAll(named);
    }
}
