package com.edteam.reservations.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.Architectures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
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
    @DisplayName("la seguridad es un detalle de infraestructura y no se filtra hacia adentro")
    void securityStaysInInfrastructure() {
        // La regla que más fácil se rompe de las que hay acá. Poner un
        // @PreAuthorize en un servicio de aplicación o leer el
        // SecurityContextHolder desde un caso de uso resuelve el problema del
        // día y ata la lógica de negocio al framework: a partir de ahí, la
        // autorización sólo funciona si el pedido entró por HTTP, y el próximo
        // adaptador de entrada —un consumidor de mensajería, un job— queda sin
        // ninguna.
        //
        // El dominio SÍ decide quién puede ver qué: eso es ReservationAccessPolicy,
        // que trabaja sobre un Actor propio y no conoce JWT ni Authentication.
        noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.security..",
                        "com.nimbusds..",
                        "jakarta.servlet..",
                        BASE + ".infrastructure.security..")
                .because("la autenticación es del adaptador; la autorización de negocio, del dominio")
                .check(productionClasses);
    }

    @Test
    @DisplayName("el actor de dominio no depende de cómo se lo autenticó")
    void domainActorIsFrameworkAgnostic() {
        // Complementa la regla anterior por el otro lado: si el Actor llegara a
        // envolver un Jwt o una Authentication, la política de acceso dejaría de
        // poder probarse sin levantar un contexto y la regla de negocio pasaría
        // a depender del emisor de turno.
        noClasses().that().resideInAPackage(BASE + ".domain.access..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        BASE + ".domain..",
                        "java..")
                .because("el actor y la política de acceso son modelo de negocio, no del borde")
                .check(productionClasses);
    }

    @Test
    @DisplayName("la mensajería es un detalle de infraestructura y no se filtra hacia adentro")
    void messagingStaysInInfrastructure() {
        // La regla que este paso agrega, y la que más fácil se rompe cuando se
        // implementa un broker: una anotación @RabbitListener en un servicio de
        // aplicación, un Message que cruza un puerto, un envelope que se cuela
        // en un evento de dominio. A partir de ahí el transporte deja de poder
        // cambiarse sin tocar la lógica, los puertos empiezan a cambiar de
        // firma para acomodarlo y los tests del caso de uso necesitan un broker
        // levantado.
        //
        // El outbox y el mapper de payload también quedan afuera: el payload se
        // serializa en infraestructura porque el dominio no sabe serializarse.
        noClasses().that().resideInAnyPackage(BASE + ".domain..", BASE + ".application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.amqp..",
                        "com.rabbitmq..",
                        BASE + ".infrastructure.adapter.out.messaging..",
                        BASE + ".infrastructure.adapter.in.messaging..",
                        BASE + ".infrastructure.adapter.out.outbox..")
                .because("el broker es un detalle de despliegue: los casos de uso no saben por dónde salen los eventos")
                .check(productionClasses);
    }

    @Test
    @DisplayName("los eventos de dominio no se contaminan con el envelope del mensaje")
    void domainEventsAreTransportAgnostic() {
        // Complementa la regla anterior por el otro lado. El messageId, el
        // sequence, la versión de esquema y el correlationId son del
        // transporte: se arman en infraestructura a partir de la fila del
        // outbox. Si vivieran en el evento, el mismo hecho tendría forma
        // distinta según por dónde sale, y cambiar de broker obligaría a tocar
        // el dominio.
        noClasses().that().resideInAPackage(BASE + ".domain.event..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        BASE + ".domain..",
                        "java..")
                .because("un hecho de negocio es el mismo hecho cualquiera sea el transporte")
                .check(productionClasses);
    }

    @Test
    @DisplayName("ningún método transaccional alcanza el catálogo de aeropuertos")
    void transactionsDoNotReachTheAirportCatalog() {
        // La validación de los aeropuertos es HTTP contra un servicio externo:
        // hasta tres intentos y ~6,5 s de peor caso por ciudad. Adentro de una
        // transacción, un itinerario de tres tramos contra un catálogo
        // degradado retiene una conexión del pool ~26 s; con maximum-pool-size
        // de 20, veinte pedidos así agotan el pool y la API entera devuelve
        // error, incluidos los GET que no tocan el catálogo ni escriben nada.
        //
        // La regla es estructural y no una convención: las clases que abren
        // transacción (*Transaction) no pueden depender del puerto del
        // catálogo. La validación ocurre antes, en el caso de uso.
        noClasses().that().haveSimpleNameEndingWith("Transaction")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        BASE + ".application.port.out.AirportCatalogPort")
                .because("una llamada de red adentro de una transacción convierte la lentitud del proveedor "
                        + "en agotamiento del pool de conexiones y en una caída de toda la API")
                .check(productionClasses);
    }

    @Test
    @DisplayName("ninguna clase con un método transaccional puede alcanzar el catálogo de aeropuertos")
    void noTransactionalClassReachesTheAirportCatalog() {
        // El otro lado de la misma regla, y el que de verdad la sostiene: si
        // CreateReservationService o ModifyReservationService volvieran a tener
        // @Transactional, la llamada al catálogo estaría otra vez adentro de la
        // transacción y la regla de arriba seguiría en verde, porque la
        // dependencia estaría en una clase que no se llama *Transaction.
        //
        // Se escribe a mano y no con el DSL porque la condición cruza dos
        // dimensiones —una anotación en los métodos y una dependencia de la
        // clase— y así el mensaje del fallo nombra al culpable.
        List<String> offenders = productionClasses.stream()
                .filter(HexagonalArchitectureTest::isTransactional)
                .filter(HexagonalArchitectureTest::reachesTheAirportCatalog)
                .map(JavaClass::getName)
                .sorted()
                .toList();

        assertThat(offenders)
                .withFailMessage("""
                        Estas clases abren transacción y alcanzan el maestro de aeropuertos: %s

                        La validación es HTTP contra un servicio externo, con hasta 3 intentos y
                        ~6,5 s de peor caso por ciudad. Adentro de la transacción, un itinerario de
                        tres tramos contra un catálogo degradado retiene una conexión del pool
                        ~26 s; con maximum-pool-size: 20, veinte pedidos así agotan el pool y la API
                        entera devuelve error, incluidos los GET que no tocan el catálogo.

                        Hay que validar primero y abrir la transacción después (ver
                        CreateReservationService / CreateReservationTransaction).""", offenders)
                .isEmpty();
    }

    private static boolean isTransactional(JavaClass javaClass) {
        return javaClass.isAnnotatedWith(Transactional.class)
                || javaClass.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(Transactional.class));
    }

    private static boolean reachesTheAirportCatalog(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName())
                .anyMatch(name -> name.equals(BASE + ".application.port.out.AirportCatalogPort")
                        || name.equals(BASE + ".application.service.AirportExistenceValidator"));
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
