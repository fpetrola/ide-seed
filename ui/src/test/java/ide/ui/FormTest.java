package ide.ui;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A capability's schema as fields to fill: what each offers comes from the IDE, the caret's element comes chosen. */
class FormTest {

    private static final Ide IDE = new Ide(Workspace.of(Map.of("a/A.java", """
            package a;

            public class A {
                public int x;

                int m(int p) {
                    return x + p;
                }
            }
            """)));

    @Test
    void eachParameterIsAFieldAndTheElementUnderTheCaretComesChosenWhereItFits() {
        var form = Form.of(IDE.capability("rename"), IDE, Optional.of(new Form.Selection("a.A#x", "FIELD")));

        assertThat(form.fields()).extracting(field -> field.param().name()).containsExactly("element", "name");
        assertThat(form.fields().getFirst().options()).contains("a.A", "a.A#x", "a.A#m(int)", "a.A#m(int)/p");
        assertThat(form.fields().getFirst().value()).isEqualTo("a.A#x");
        assertThat(form.fields().get(1).value()).isNull();
    }

    @Test
    void theKindsAParameterTakesNarrowWhatItOffers() {
        var form = Form.of(IDE.capability("encapsulate-field"), IDE, Optional.of(new Form.Selection("a.A", "CLASS")));

        assertThat(form.fields().getFirst().options()).containsExactly("a.A#x");
        assertThat(form.fields().getFirst().value()).isNull();
    }

    @Test
    void theValuesAreTheCapabilitysArgumentsWithoutTheOptionalOnesLeftEmpty() {
        var form = Form.of(IDE.capability("create"), IDE, Optional.empty());

        assertThat(form.fields()).extracting(field -> field.param().name()).containsExactly("kind", "parent", "name", "facts", "argument");
        assertThat(form.fields().getFirst().options()).contains("CLASS", "FIELD", "METHOD");
        assertThat(form.args(Map.of("kind", "FIELD", "parent", "a.A", "name", "y", "facts", "", "argument", "")))
                .isEqualTo(Map.of("kind", "FIELD", "parent", "a.A", "name", "y"));
        assertThat(Form.of(IDE.capability("extract-superclass"), IDE, Optional.empty()).fields().get(2).value()).isEqualTo(List.of());
    }
}
