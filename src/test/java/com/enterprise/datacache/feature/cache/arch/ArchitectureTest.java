package com.enterprise.datacache.feature.cache.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Guards portability of the {@code feature.cache} package: it must be copyable into any Spring
 * Boot 3 application unchanged, so it must never depend on the standalone demo application class
 * that hosts it here, and web/REST concerns must stay confined to {@code feature.cache.api} so the
 * rest of the feature can be embedded by a host that supplies its own REST layer.
 */
class ArchitectureTest {

    private final JavaClasses cacheClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.enterprise.datacache");

    @Test
    void featureCacheMustNotDependOnTheStandaloneDemoApplication() {
        ArchRule rule = noClasses().that().resideInAPackage("..feature.cache..")
                .should().dependOnClassesThat().resideInAPackage("com.enterprise.datacache");
        rule.check(cacheClasses);
    }

    @Test
    void onlyFeatureCacheApiMayDependOnWebMvcOrRestControllers() {
        ArchRule rule = noClasses().that().resideInAPackage("..feature.cache..")
                .and().resideOutsideOfPackage("..feature.cache.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web.bind.annotation..", "org.springframework.web.servlet..");
        rule.check(cacheClasses);
    }
}
