package ide.cli;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * An adapter knows the session and the core, nothing below them: it translates to capabilities, it decides nothing.
 * Maven cannot say it, since what the session declares reaches the adapter too.
 */
@AnalyzeClasses(packages = "ide.cli", importOptions = ImportOption.DoNotIncludeTests.class)
class AdapterTest {

    @ArchTest
    static final ArchRule onlyTheSessionAndTheCore = classes().that().resideInAPackage("ide.cli..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.cli..", "ide.session..", "ide.core..", "java..");
}
