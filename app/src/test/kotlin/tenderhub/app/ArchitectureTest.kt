package tenderhub.app

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("tenderhub")

    private val modules = listOf("users", "tenders", "bidding", "notifications")

    @Test
    fun `business logic does not depend on frameworks, web or database`() {
        noClasses().that().resideInAnyPackage("tenderhub..domain..", "tenderhub..application..", "tenderhub..api..", "tenderhub.common")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "java.sql..",
                "javax.sql..",
                "com.fasterxml..",
                "tenderhub..adapter..",
                "tenderhub.app..",
            )
            .check(classes)
    }

    @Test
    fun `modules use only public API of other modules`() {
        for (module in modules) {
            for (other in modules - module) {
                noClasses().that().resideInAPackage("tenderhub.$module..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "tenderhub.$other.adapter..",
                    )
                    .check(classes)
            }
        }
        noClasses().that().resideInAPackage("tenderhub.bidding..")
            .should().dependOnClassesThat().resideInAnyPackage("tenderhub.tenders.application..", "tenderhub.users.application..")
            .check(classes)
        noClasses().that().resideInAPackage("tenderhub.notifications..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "tenderhub.users.application..",
                "tenderhub.tenders.application..",
                "tenderhub.bidding.application..",
                "tenderhub.bidding.domain..",
            )
            .check(classes)
    }

    @Test
    fun `module dependencies point one way`() {
        noClasses().that().resideInAPackage("tenderhub.users..")
            .should().dependOnClassesThat().resideInAnyPackage("tenderhub.tenders..", "tenderhub.bidding..", "tenderhub.notifications..")
            .check(classes)
        noClasses().that().resideInAPackage("tenderhub.tenders..")
            .should().dependOnClassesThat().resideInAnyPackage("tenderhub.bidding..", "tenderhub.notifications..")
            .check(classes)
        noClasses().that().resideInAPackage("tenderhub.bidding..")
            .should().dependOnClassesThat().resideInAnyPackage("tenderhub.notifications..")
            .check(classes)
    }
}
