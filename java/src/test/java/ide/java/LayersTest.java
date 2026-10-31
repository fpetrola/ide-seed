package ide.java;

import com.github.javaparser.JavaParser;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/** The Java driver in layers: each knows only those below it; only the text layer parses; nothing reprints a tree. */
@AnalyzeClasses(packages = "ide.java", importOptions = ImportOption.DoNotIncludeTests.class)
class LayersTest {

    @ArchTest
    static final ArchRule eachLayerKnowsOnlyTheOnesBelow = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("Driver").definedBy("ide.java")
            .layer("Primitives").definedBy("ide.java.primitives..")
            .layer("Semantics").definedBy("ide.java.semantics..")
            .layer("Syntax").definedBy("ide.java.syntax..")
            .layer("Text").definedBy("ide.java.text..")
            .whereLayer("Driver").mayNotBeAccessedByAnyLayer()
            .whereLayer("Primitives").mayOnlyBeAccessedByLayers("Driver")
            .whereLayer("Semantics").mayOnlyBeAccessedByLayers("Primitives", "Driver")
            .whereLayer("Syntax").mayOnlyBeAccessedByLayers("Semantics", "Primitives", "Driver")
            .whereLayer("Text").mayOnlyBeAccessedByLayers("Syntax", "Semantics", "Primitives", "Driver");

    @ArchTest
    static final ArchRule onlyTheTextLayerParses = noClasses()
            .that().resideOutsideOfPackage("ide.java.text..")
            .should().dependOnClassesThat().areAssignableTo(JavaParser.class);

    @ArchTest
    static final ArchRule nothingReprintsATree = noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.github.javaparser.printer.lexicalpreservation..");
}
