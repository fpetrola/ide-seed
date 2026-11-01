package ide.mcp;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/** An adapter knows the session and the core, nothing below them: the server translates, it decides nothing. */
@AnalyzeClasses(packages = "ide.mcp", importOptions = ImportOption.DoNotIncludeTests.class)
class AdapterTest {

    @ArchTest
    static final ArchRule onlyTheSessionAndTheCore = classes().that().resideInAPackage("ide.mcp..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.mcp..", "ide.session..", "ide.core..", "java..",
                    "com.sun.net.httpserver..");
}
