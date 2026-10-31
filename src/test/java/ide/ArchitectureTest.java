package ide;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "ide", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule languageSemanticsLiveInTheProjectionNotInTheCore = noClasses()
            .that().resideInAnyPackage("ide.graph..", "ide.coherence..", "ide.refactor..")
            .should().dependOnClassesThat().resideInAnyPackage("com.github.javaparser..", "ide.java..");

    @ArchTest
    static final ArchRule graphKnowsNothingAboveIt = noClasses()
            .that().resideInAPackage("ide.graph..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.coherence..", "ide.refactor..", "ide.workbench..");

    @ArchTest
    static final ArchRule projectionDoesNotKnowTheEngineOrRefactorings = noClasses()
            .that().resideInAPackage("ide.java..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.coherence..", "ide.refactor..", "ide.workbench..");

    @ArchTest
    static final ArchRule refactoringsDoNotKnowTheEngine = noClasses()
            .that().resideInAPackage("ide.refactor..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.coherence..", "ide.workbench..");

    @ArchTest
    static final ArchRule coreKnowsNothingElse = noClasses()
            .that().resideInAPackage("ide.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "ide.graph..", "ide.java..", "ide.coherence..", "ide.refactor..", "ide.workbench..", "com.github..");

    @ArchTest
    static final ArchRule theEngineDoesNotKnowTheKnowledge = noClasses()
            .that().resideInAnyPackage("ide.coherence..", "ide.graph..", "ide.refactor..", "ide.java..")
            .should().dependOnClassesThat().resideInAPackage("ide.knowledge..");

    @ArchTest
    static final ArchRule knowledgeIsLanguageNeutral = noClasses()
            .that().resideInAPackage("ide.knowledge..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.java..", "com.github.javaparser..");

    @ArchTest
    static final ArchRule noRemedyIsWrittenInJava = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes()
            .that().implement(ide.coherence.Remedy.class).or().implement(ide.coherence.QuickFix.class)
            .should().haveFullyQualifiedName("ide.knowledge.Knowledge");
}
