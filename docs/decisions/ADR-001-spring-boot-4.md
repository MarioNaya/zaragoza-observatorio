# ADR-001 — Spring Boot 4.1.x en lugar de 3.x

- **Fecha**: 2026-09-05
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §4.2 (Stack), §8 regla 15

## Contexto

`SPEC.md` v0.2 fijaba "Java 21, Spring Boot 3.x, Spring Modulith". Al generar el proyecto con Spring Initializr (regla 15: scaffolding con la herramienta, nunca de memoria) se comprobó el 2026-09-05 que:

- Initializr solo ofrece Spring Boot 4.0.8, 4.1.1 (por defecto) y milestones 4.2.x. Ya no ofrece ninguna 3.x.
- La dependencia `modulith` de Initializr declara el rango de compatibilidad `[4.0.0, 4.3.0-M1)`: Spring Modulith exige Boot ≥ 4.0.
- Con Boot 4.1.1 el BOM importado es `spring-modulith-bom` 2.1.1.

Insistir en Boot 3.x habría obligado a escribir el `pom.xml` a mano con versiones recordadas, en contra de la regla 15.

## Decisión

Generar el proyecto con **Spring Boot 4.1.1, Java 21 y Spring Modulith 2.1.1** (versiones resueltas por Initializr). Las versiones posteriores dentro de la línea 4.1.x se adoptan al actualizar el parent.

## Consecuencias

- Starters modulares de Boot 4: `spring-boot-starter-webmvc` (no `-web`), `spring-boot-starter-restclient`, `spring-boot-starter-flyway`, y starters de test por módulo (`spring-boot-starter-webmvc-test`, `-data-jpa-test`, …).
- Testcontainers 2.x: artefactos `org.testcontainers:testcontainers-postgresql` y `testcontainers-junit-jupiter`; los paquetes cambiaron respecto a 1.x. Se toman de los ficheros que genera Initializr.
- Jackson 3 (`tools.jackson.*`) es el JSON por defecto de Boot 4. Se usa el que traiga el classpath.
- Dependencias fuera de los BOM de Boot y Modulith (Resilience4j, ShedLock, ArchUnit, WireMock) deben verificarse contra Maven Central en cuanto a versión y compatibilidad con Boot 4 antes de añadirse.
- `SPEC.md` §4.2 pasa a decir "Spring Boot 4.1.x".
