# ADR-000 — La especificación inicial es la decisión fundacional

- **Fecha**: 2026-09-05
- **Estado**: aceptada

## Contexto

El proyecto arranca con un documento de especificación (`SPEC.md`) que fija propósito, principios no negociables, fuentes de datos, fases, arquitectura (monolito modular con Spring Modulith y hexagonal por módulo), estrategia de pruebas, riesgos y reglas de trabajo.

## Decisión

`SPEC.md`, en la raíz del repositorio, es la ADR-000. Es un documento vivo: se actualiza cuando los spikes resuelven una incógnita o cuando una ADR posterior modifica una decisión. Cada ADR posterior referencia la sección de `SPEC.md` que afecta.

## Consecuencias

- Las ADR siguientes son deltas sobre `SPEC.md`, no documentos autosuficientes.
- Un cambio de `SPEC.md` que no venga respaldado por un spike o una ADR es sospechoso y debe revisarse.
