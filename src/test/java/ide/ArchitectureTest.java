package ide;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The module map of ARCHITECTURE.md: every module is a product that knows only what is below it. */
@AnalyzeClasses(packages = "ide", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreKnowsNothingElse = classes()
            .that().resideInAPackage("ide.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.core..", "java..");

    @ArchTest
    static final ArchRule programDependsOnlyOnCore = classes()
            .that().resideInAPackage("ide.program..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.program..", "ide.core..", "java..");

    @ArchTest
    static final ArchRule projectionIsAContractOverTheProgram = classes()
            .that().resideInAPackage("ide.projection..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("ide.projection..", "ide.program..", "ide.core..", "java..");

    @ArchTest
    static final ArchRule onlyTheJavaDriverKnowsJavaParserAndJavac = noClasses()
            .that().resideOutsideOfPackage("ide.java..")
            .should().dependOnClassesThat().resideInAnyPackage("com.github.javaparser..", "javax.tools..", "com.sun.source..");

    @ArchTest
    static final ArchRule theJavaDriverKnowsNothingAboveTheContract = noClasses()
            .that().resideInAPackage("ide.java..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.engine..", "ide.refactoring..", "ide.knowledge..", "ide.session..");

    @ArchTest
    static final ArchRule theEngineKnowsNoLanguageAndNothingAbove = noClasses()
            .that().resideInAPackage("ide.engine..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.java..", "ide.refactoring..", "ide.knowledge..", "ide.session..");

    @ArchTest
    static final ArchRule refactoringsKnowNoLanguageNoKnowledgeNoSession = noClasses()
            .that().resideInAPackage("ide.refactoring..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.java..", "ide.knowledge..", "ide.session..");

    @ArchTest
    static final ArchRule knowledgeIsLanguageNeutralAndKnowsNoSession = noClasses()
            .that().resideInAPackage("ide.knowledge..")
            .should().dependOnClassesThat().resideInAnyPackage("ide.java..", "ide.session..");

    @ArchTest
    static final ArchRule theSessionIsTheOnlyAssembler = noClasses()
            .that().resideOutsideOfPackage("ide.session..")
            .should().dependOnClassesThat().resideInAPackage("ide.session..");

    @ArchTest
    static final ArchRule noRemedyIsWrittenInJava = classes()
            .that().implement(ide.engine.Remedy.class).or().implement(ide.engine.QuickFix.class)
            .should().haveFullyQualifiedName("ide.knowledge.Knowledge");
}
