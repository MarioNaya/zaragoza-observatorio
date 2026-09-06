# ADR-004 — Registro de eventos sobre JDBC con Flyway, Resilience4j programático y aplazamiento de ShedLock y WireMock

- **Fecha**: 2026-09-06
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §4.2 (Stack), §4.5 (flujo de ingesta), §6 (adaptadores upstream); `docs/ESTADO.md` §4 puntos 1 y 2
- **Se apoya en**: `docs/spikes/S0.5-api.md` (reglas del cliente HTTP), ADR-001 (Boot 4)

## Contexto

Al abrir la fase 1 quedaban cuatro decisiones de infraestructura sin cerrar. Todo lo que sigue se comprobó el 2026-09-06 contra los artefactos descargados en el repositorio local de Maven y contra `repo1.maven.org` (regla 15), no de memoria.

1. **Registro de publicación de eventos de Modulith.** El esqueleto traía `spring-modulith-starter-jpa`, que persiste `event_publication` como entidad JPA gestionada por Hibernate. Con PostgreSQL, `spring.jpa.hibernate.ddl-auto` vale `none` por defecto, así que la tabla no existía (los tests pasaban porque aún no se publica ningún evento). Pasar a `validate` con el starter JPA obligaría a replicar a mano el mapeo exacto de una entidad ajena. La alternativa `spring-modulith-starter-jdbc` 2.1.1 (en el BOM de Modulith) trae los scripts de esquema por base de datos dentro del jar (`org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql`), su inicialización automática viene desactivada (`spring.modulith.events.jdbc.schema-initialization.enabled=false`) y usa la estructura v2 salvo que se active `use-legacy-structure`.
2. **Resiliencia del cliente HTTP.** `SPEC.md` §4.2 pide Resilience4j. En Maven Central existen Resilience4j 2.4.0 y un starter `resilience4j-spring-boot4` 2.4.0 compilado contra `spring-boot-autoconfigure` 4.0.0. Spring Framework 7.0.9 (el de Boot 4.1.1) incorpora además `@Retryable` y `@ConcurrencyLimit` en `spring-context`, pero no circuit breaker. El uso por anotaciones exige proxies AOP sobre el adaptador y dificulta probarlo sin contexto de Spring.
3. **ShedLock.** Existe 7.10.0 (fuera de todo BOM). `SPEC.md` §5 fija una sola instancia y el objetivo de ShedLock es evitar ejecuciones concurrentes entre instancias.
4. **WireMock para los fixtures.** WireMock 4 sigue en beta (`4.0.0-beta.38`); la rama estable es 3.13.2, fuera de todo BOM y con Jackson 2 y Jetty en el classpath de test. `spring-test` (ya presente vía los starters de test) trae `MockRestServiceServer`, que se enlaza a un `RestClient.Builder` y permite responder con cuerpo, cabeceras y códigos de estado arbitrarios, en secuencia.

## Decisión

1. **Eventos sobre JDBC, esquema en Flyway.** Se sustituye `spring-modulith-starter-jpa` por `spring-modulith-starter-jdbc`. La tabla `event_publication` se crea en `V002__event_publication.sql`, copia literal del script v2 para PostgreSQL del jar 2.1.1. `spring.jpa.hibernate.ddl-auto=validate`: Flyway es el único dueño del esquema y Hibernate solo comprueba que las entidades casan. `spring.modulith.events.republish-outstanding-events-on-restart=true`: con una sola instancia, las publicaciones incompletas se reintentan al arrancar. El modo de completado se deja en el predeterminado (`update`); si el volumen de eventos lo justifica se pasará a `archive` con la tabla `event_publication_archive` del mismo jar en una migración nueva.
2. **Resilience4j core, uso programático.** Se añaden `resilience4j-retry` y `resilience4j-circuitbreaker` 2.4.0 (propiedad `resilience4j.version` en el `pom.xml`, verificada en `repo1.maven.org` el 2026-09-06; dependen solo de `resilience4j-core` y `slf4j-api`). Se usan como objetos dentro del adaptador HTTP de `ingestion`, sin starter ni anotaciones: el adaptador se prueba como clase normal. Política según S0.5: reintento con backoff solo ante 5xx, timeouts y errores de E/S (nunca ante 4xx ni ante 404 JSON «Registro no encontrado»), circuit breaker por fuente, y límite de 4 peticiones concurrentes por host con un semáforo. El starter `resilience4j-spring-boot4` se descarta por estar ligado a Boot 4.0.0 y por el acoplamiento AOP.
3. **ShedLock aplazado.** Los jobs se ejecutan en serie con `spring.task.scheduling.pool.size=1` y `@Scheduled(fixedDelay=…)`, lo que impide solapamientos dentro de la instancia. ShedLock se incorporará (con verificación previa de versión) solo si se despliega más de una instancia; queda anotado en `SPEC.md` §9.
4. **`MockRestServiceServer` en lugar de WireMock.** Los tests de los adaptadores anti-corrupción usan `MockRestServiceServer` sobre los fixtures grabados en `src/test/resources/fixtures/zaragoza/` (que siguen refrescándose con los spikes, ADR-002), incluyendo cabeceras reales como `Last-Modified` con zona `CEST`. WireMock se reconsiderará si hace falta simular latencias o timeouts reales; hasta entonces el criterio de reintento se prueba unitariamente sobre las excepciones que produce `RestClient`.
5. **springdoc-openapi 3.1.0 para el contrato de la API propia.** `SPEC.md` §6 dejaba elegir entre Spring REST Docs (en el BOM de Boot) y springdoc. Se elige springdoc porque produce el documento OpenAPI 3 (`/v3/api-docs`) y Swagger UI (`/swagger-ui.html`) sin escribir snippets, y la API es «un producto para otros reutilizadores» (§4.9). La 3.1.0 se construye sobre `spring-boot-starter-parent` 4.1.0 (comprobado en su POM el 2026-09-06); propiedad `springdoc.version` en el `pom.xml`. Un test de integración comprueba que el documento lista las cuatro rutas del catálogo.

## Consecuencias

- El `pom.xml` gana dos dependencias con versión explícita (Resilience4j) y cambia un starter de Modulith; `.\mvnw.cmd verify` debe seguir en verde con Testcontainers tras el cambio.
- Toda entidad JPA nueva necesita su migración Flyway antes de arrancar: `validate` falla si falta una columna. Es el comportamiento deseado.
- Los listeners de eventos entre módulos (`@ApplicationModuleListener`) quedan registrados en `event_publication`; el endpoint `/actuator/modulith` sigue disponible y la tabla puede inspeccionarse con SQL para depurar entregas pendientes.
- El adaptador HTTP de `ingestion` concentra toda la resiliencia; si más adelante se quisiera exponer el estado de los circuit breakers en Actuator, se hará con un `HealthIndicator` propio, no con el starter.
- `SPEC.md` §4.2 pasa a decir «Resilience4j (retry, circuit breaker) en uso programático; ShedLock aplazado; `MockRestServiceServer` sobre fixtures» y §6 sustituye «WireMock» por «`MockRestServiceServer` (WireMock si hace falta simular latencias)».

## Alternativas descartadas

- **Mantener `spring-modulith-starter-jpa` con `ddl-auto=update`**: Hibernate pasaría a tocar el esquema en producción, en contra de tener Flyway como única fuente de verdad.
- **Dejar que Modulith inicialice el esquema** (`schema-initialization.enabled=true`): dos herramientas creando tablas en la misma base de datos, sin historial de versiones.
- **`resilience4j-spring-boot4` con `@CircuitBreaker`/`@Retry`**: acoplado a Boot 4.0.0, proxies AOP sobre el adaptador y configuración por propiedades que no se puede probar sin contexto.
- **Solo `@Retryable` de Spring Framework 7**: cubre el reintento pero no el circuit breaker por fuente que exige S0.5; se habría acabado escribiendo uno a mano.
- **Circuit breaker propio**: menos de cien líneas, pero reinventa un componente maduro y probado.
- **ShedLock desde el principio**: una tabla y una dependencia más para un problema (varias instancias) que hoy no existe.
- **WireMock 3.13.2**: válido, pero añade Jetty y Jackson 2 al classpath de test para una capacidad (simular latencias) que ahora no se necesita.
