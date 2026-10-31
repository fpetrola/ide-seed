package ide.acceptance;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The module map of ARCHITECTURE.md is enforced by the Maven modules: a product compiles only against the products
 * it declares. These are the rules Maven cannot state: what leaks through transitive dependencies, and who may
 * implement what.
 */
@AnalyzeClasses(packages = "ide", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule onlyTheJavaDriverKnowsJavaParserAndJavac = noClasses()
            .that().resideOutsideOfPackage("ide.java..")
            .should().dependOnClassesThat().resideInAnyPackage("com.github.javaparser..", "javax.tools..", "com.sun.source..");

    @ArchTest
    static final ArchRule theJavaDriverIsUsedOnlyThroughItsFacade = noClasses()
            .that().resideOutsideOfPackage("ide.java..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ide.java.text..", "ide.java.syntax..", "ide.java.semantics..", "ide.java.primitives..");

    @ArchTest
    static final ArchRule noRemedyIsWrittenInJava = classes()
            .that().implement(ide.engine.Remedy.class).or().implement(ide.engine.QuickFix.class)
            .should().haveFullyQualifiedName("ide.knowledge.Knowledge");
}
