package com.enterprise.datacache.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;

/**
 * Guards module portability: {@code data-cache-core} must never depend on the standalone
 * {@code data-cache-app} REST application, or the reusable module could no longer be embedded
 * into another Spring Boot service without dragging in web/REST concerns.
 */
class ArchitectureTest {

    private final JavaClasses coreClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.enterprise.datacache");

    @Test
    void coreMustNotDependOnStandaloneApp() {
        ArchRule rule = noClasses().should().dependOnClassesThat().resideInAPackage("..datacache.app..");
        rule.check(coreClasses);
    }

    @Test
    void coreMustNotDependOnWebMvcOrRestControllers() {
        ArchRule rule = noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web.bind.annotation..", "org.springframework.web.servlet..");
        rule.check(coreClasses);
    }
}
