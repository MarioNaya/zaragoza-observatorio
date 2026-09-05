# ADR-002 — Los spikes son tests JUnit etiquetados dentro del repositorio

- **Fecha**: 2026-09-05
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §3 (Fase 0), §6 (fixtures), §8 regla 2

## Contexto

`SPEC.md` define un spike como "un script o test exploratorio más un informe en `docs/spikes/`" y exige que los fixtures de respuestas reales "se refresquen con un script, no a mano". Había tres opciones: tests JUnit en el propio proyecto Maven, scripts Python (Python 3.12 está instalado) o exploración manual con `curl`.

## Decisión

Cada spike es una clase de test JUnit 5 en `src/test/java/es/zaragoza/observatory/spikes/`, nombrada `S0xNombreSpike`, anotada con `@Tag("spike")`.

- El build por defecto (`.\mvnw.cmd verify`) **excluye** el grupo `spike` (surefire `excludedGroups`).
- El perfil Maven `spikes` los ejecuta: `.\mvnw.cmd test -Pspikes` (o `-Pspikes "-Dtest=S01*"`).
- Usan `RestClient` y el Jackson del classpath; no usan retry ni circuit breaker para observar el comportamiento real de la API.
- Guardan las respuestas crudas en `src/test/resources/fixtures/zaragoza/<fuente>/` y las métricas en `target/spikes/`.
- El informe humano va en `docs/spikes/S0.x-nombre.md` con la plantilla de `docs/spikes/README.md`.

## Consecuencias

- Un solo ecosistema (Java/Maven); las utilidades HTTP descubiertas en los spikes se trasladan a los adaptadores anti-corrupción de fase 1.
- Las respuestas grabadas sirven directamente como fixtures WireMock (§6) y se refrescan reejecutando el spike.
- Los spikes dependen de la red y de la API municipal: nunca forman parte de CI ni del build por defecto.
- La exploración es algo más verbosa que en Python; se acepta a cambio de la reutilización.
