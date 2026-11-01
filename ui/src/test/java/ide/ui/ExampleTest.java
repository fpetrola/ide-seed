package ide.ui;

import ide.projection.Diagnostic;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The example project does what its comments say: each thing they tell to try works there. */
class ExampleTest {

    @TempDir
    Path root;

    private Workspace example() {
        return Workspace.open(Main.example(root));
    }

    @Test
    void itsOnlyProblemsAreTheOnesLeftForTheQuickFixes() {
        assertThat(example().diagnostics()).extracting(Diagnostic::path).containsOnly("shop/fixes/Broken.java", "shop/fixes/Unset.java");
    }

    @Test
    void theRefactoringsItProposesApply() {
        for (var proposed : java.util.List.of(
                Map.of("refactoring", "encapsulate-field", "field", "shop.model.Product#price"),
                Map.of("refactoring", "rename", "element", "shop.model.Product#name", "name", "title"),
                Map.of("refactoring", "pull-up-unifying", "member", "shop.model.Book#title"),
                Map.of("refactoring", "pull-up-unifying", "member", "shop.model.Book#describe()"),
                Map.of("refactoring", "pull-up-field", "field", "shop.model.Book#pages"),
                Map.of("refactoring", "introduce-factory", "constructor", "shop.model.Discount#new(int)", "name", "of"),
                Map.of("refactoring", "merge-duplicate", "keep", "shop.model.Discount#percent()", "duplicate", "shop.model.Discount#rate()"),
                Map.of("refactoring", "inline-method", "method", "shop.cart.Cart#subtotal(double)"),
                Map.of("refactoring", "extract-method", "method", "shop.cart.Cart#total()", "lines", "31-32", "name", "count"),
                Map.of("refactoring", "collapse-hierarchy", "type", "shop.report.Report.Line"),
                Map.of("refactoring", "generate-getter", "field", "shop.report.Report.Entry#label"))) {
            var args = new java.util.HashMap<String, Object>(proposed);
            var refactoring = (String) args.remove("refactoring");

            var result = example().invoke(refactoring, args);

            assertThat(result.violations()).as(proposed.toString()).isEmpty();
            assertThat(result.applied()).as(proposed.toString()).isTrue();
        }
    }

    @Test
    void theQuickFixesItLeavesFixWhatTheySay() {
        var ws = example();

        assertThat(ws.fixes("shop/fixes/Broken.java", 12)).contains("import java.util.List");
        assertThat(ws.fixes("shop/fixes/Broken.java", 13)).contains("open-visibility shop.secret.Vault package -> public, then import shop.secret.Vault");
        assertThat(ws.fixes("shop/fixes/Broken.java", 16)).contains("create field count in Broken");
        assertThat(ws.fixes("shop/fixes/Broken.java", 22)).contains("change to items", "change to size(..)");
        assertThat(ws.fixes("shop/fixes/Unset.java", 8)).contains("initialize limit in its declaration", "make limit not final");

        ws.fix("shop/fixes/Broken.java", 22, "change to items");
        ws.fix("shop/fixes/Broken.java", 22, "change to size(..)");

        assertThat(ws.diagnostics()).extracting(Diagnostic::line).doesNotContain(22);
        assertThat(ws.source("shop/fixes/Broken.java")).contains("return items + size();");

        ws.fix("shop/fixes/Broken.java", 30, "create method sum in Broken");

        assertThat(ws.diagnostics()).extracting(Diagnostic::line).doesNotContain(30);
        assertThat(ws.source("shop/fixes/Broken.java")).contains("double sum(int items, int i) {");
    }
}
