package com.edteam.reservations.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Verifica las reglas de dependencia de la arquitectura hexagonal.
 *
 * <p>Sin esto, las capas se respetan sólo por disciplina: basta un import
 * cómodo para que el dominio termine dependiendo de un adaptador y se pierda
 * todo el beneficio. Estos tests convierten esa disciplina en algo que falla
 * en el build.
 */
@DisplayName("Arquitectura hexagonal")
class HexagonalArchitectureTest {

    private static final String BASE = "com.edteam.reservations";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("las capas sólo dependen hacia adentro")
    void layersDependInwardsOnly() {
        Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Dominio").definedBy(BASE + ".domain..")
                .layer("Aplicación").definedBy(BASE + ".application..")
                .layer("Infraestructura").definedBy(BASE + ".infrastructure..")
                .layer("Arranque").definedBy(BASE)
                .whereLayer("Infraestructura").mayOnlyBeAccessedByLayers("Arranque")
                .whereLayer("Aplicación").mayOnlyBeAccessedByLayers("Infraestructura", "Arranque")
                .whereLayer("Dominio").mayOnlyBeAccessedByLayers("Aplicación", "Infraestructura", "Arranque")
                .check(productionClasses);
    }

    @Test
    @DisplayName("el dominio no conoce la aplicación ni la infraestructura")
    void domainIsIndependent() {
        noClasses().that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        BASE + ".application..",
                        BASE + ".infrastructure..")
                .because("el dominio es el centro de la hexagonal: nada de afuera puede entrar")
                .check(productionClasses);
    }

    @Test
    @DisplayName("el dominio no depende de Spring ni de ningún framework")
    void domainHasNoFrameworkDependencies() {
        noClasses().that().resideInAPackage(BASE + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "com.fasterxml..")
                .because("el modelo de negocio tiene que poder testearse y sobrevivir a un cambio de framework")
                .check(productionClasses);
    }

    @Test
    @DisplayName("la aplicación no depende de la infraestructura: la inversión de dependencias va por los puertos")
    void applicationDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage(BASE + ".application..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".infrastructure..")
                .because("los casos de uso dependen de puertos, no de adaptadores")
                .check(productionClasses);
    }

    @Test
    @DisplayName("el cache es un detalle de infraestructura y no se filtra hacia adentro")
    void cacheStaysInInfrastructure() {
        // Un cache es una decisión de despliegue: qué se guarda, dónde y por
        // cuánto tiempo. En cuanto el dominio o un caso de uso importan Redis
        // —o el almacén propio que lo envuelve— esa decisión deja de poder
        // revisarse sin tocar la lógica de negocio, y los puertos empiezan a
        // cambiar de firma para acomodarla.
        noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.data.redis..",
                        "org.springframework.cache..",
                        BASE + ".infrastructure.cache..")
                .because("el cache vive en infraestructura: los casos de uso no saben que existe")
                .check(productionClasses);
    }

    @Test
    @DisplayName("los puertos son interfaces")
    void portsAreInterfaces() {
        classes().that().resideInAPackage(BASE + ".application.port.out..")
                .should().beInterfaces()
                .check(productionClasses);
    }

    @Test
    @DisplayName("nadie depende directamente de los servicios de aplicación salvo el cableado de Spring")
    void adaptersDependOnUseCasePortsNotOnServices() {
        noClasses().that().resideInAPackage(BASE + ".infrastructure.adapter.in..")
                .should().dependOnClassesThat().resideInAPackage(BASE + ".application.service..")
                .because("los adaptadores de entrada tienen que hablar con los puertos de entrada")
                .check(productionClasses);
    }
}
