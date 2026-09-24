package com.metis.engine.core;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreArchitectureTest {
    @Test
    void coreDoesNotDependOnTransportPersistenceOrFrameworks() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.metis.engine.core");
        assertTrue(classes.size() > 0, "Architecture check must inspect production classes");
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "java.sql..", "javax.sql..", "jakarta.servlet..",
                "com.metis.engine.api..", "com.metis.web..").check(classes);
        noClasses().that().resideInAPackage("com.metis.engine.core.rules.runtime..")
                .should().dependOnClassesThat().haveNameMatching("com\\.metis\\.engine\\.core\\.(AuthoritativeGameEngine|ActivationEngine|OrderEngine|UnitDefinition|UnitState|GameState)")
                .check(classes);
        noClasses().that().haveNameMatching("com\\.metis\\.engine\\.core\\.rules\\.runtime\\.RulesCpu(\\$.*)?")
                .should().dependOnClassesThat().haveNameMatching("com\\.metis\\.engine\\.core\\.rules\\.runtime\\.(RulesState|RuntimeMachine|MechanicsState|RandomStream|RulesRuntime)$")
                .check(classes);
    }
}
