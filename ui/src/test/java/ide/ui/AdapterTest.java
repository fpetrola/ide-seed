package ide.ui;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** An adapter knows the session and the core, nothing below them: the window shows and asks, it decides nothing. */
@AnalyzeClasses(packages = "ide.ui", importOptions = ImportOption.DoNotIncludeTests.class)
class AdapterTest {

    @ArchTest
    static final ArchRule onlyTheSessionAndTheCore = classes().that().resideInAPackage("ide.ui..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.ui..", "ide.session..", "ide.core..", "java..",
                    "javax.swing..", "org.fife..");
}
