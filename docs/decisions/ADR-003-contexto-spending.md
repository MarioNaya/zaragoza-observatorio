# ADR-003 — El gasto público es el contexto `spending`, sin entidades territoriales

- **Fecha**: 2026-09-05
- **Estado**: aceptada
- **Afecta a**: `SPEC.md` §1, §3 (fase 3), §4.3, §4.6, §4.7, §7, §9
- **Se apoya en**: `docs/spikes/S0.2-ocds.md`, `docs/spikes/S0.6-inventario.md`

## Contexto

`SPEC.md` v0.2 dejaba el nombre y la composición del contexto de inversión a lo que dijera el inventario S0.6: `investment` si aparecían fuentes adicionales con dimensión territorial (presupuestos participativos, obras), `contracting` si solo había OCDS. Los spikes dieron un tercer resultado:

- **OCDS** (S0.2): 139 release packages analizados, **ningún campo de localización** (`deliveryLocation`, `deliveryAddress`, direcciones, coordenadas). Solo un 19 % menciona una calle o barrio en texto libre, sin garantía de que sea el lugar de ejecución.
- **Presupuesto** (S0.6): `presupuesto/gasto-corriente` con crédito inicial, definitivo, comprometido, obligación neta y pago neto por área, programa, órgano, capítulo y partida, en 140 snapshots datados; resúmenes anuales presupuestado/gastado. Sin territorio.
- **Subvenciones** (S0.6): `ayuda-subvencion` con adjudicatario, importe, aplicación presupuestaria, área, línea e instrumento. Sin territorio (`municipioId` = Zaragoza).
- **Presupuestos participativos**: ningún dataset ni endpoint en el catálogo ni en el Swagger.
- **Obras**: `via-publica/incidencia` son cortes y obras en curso, sin importes; `licencia-obra` y `registro-licencia` son licencias **privadas** geolocalizadas: miden actividad edificatoria y económica, no inversión pública.

Hay, por tanto, más fuentes de gasto que OCDS, pero **ninguna con dimensión territorial**. Ni `investment` (promete un territorio que no existe) ni `contracting` (ignora presupuesto y subvenciones) describen lo que hay.

## Decisión

1. El bounded context de gasto público se llama **`spending`** y agrupa tres fuentes: contratación OCDS, presupuesto municipal (gastos e ingresos, con snapshots de ejecución) y subvenciones.
2. Sus entidades son `ContractingProcess`, `Award`, `Contract`, `Supplier`, `BudgetSnapshot`, `BudgetLine` y `Grant`, según `SPEC.md` §4.6. **Ninguna lleva punto, dirección, barrio ni junta.** Todo registro de gasto lleva `stage` (`planned` / `committed` / `executed`) y conserva `sourceDataset` e `ingestedAt`.
3. `spending` **no depende de `geo`** y `territory` **no depende de `spending`**. Las agregaciones de `spending` son por área, órgano, programa, adjudicatario, categoría, CPV, año y `stage`; nunca por territorio.
4. La API expone el hecho explícitamente: las respuestas de `/api/v1/spending/*` incluyen en `caveats` que el gasto municipal publicado no es territorializable con los datos abiertos actuales.
5. **No se geocodifica texto** (títulos, descripciones, direcciones de partes) para inventar una localización: violaría las reglas 1 y 6 de `SPEC.md` §8.
6. Licencias de obra, licencias de locales, locales vacíos y obras en vía pública quedan **fuera de `spending`**. Son candidatos a un contexto posterior de actividad urbanística (`urban-activity`), que requerirá su propia ADR y no está en el alcance de las fases 1–5.

## Consecuencias

- La capacidad 2 de `SPEC.md` §1 pasa de "observatorio de inversión" a **observatorio de gasto público sin territorio**. El eje territorial del producto se sostiene con `citizen` (incidencias geolocalizadas), `geo` (juntas y padrón) y sociodemografía; la comparación inversión–quejas queda condicionada a que el ayuntamiento publique inversión por junta.
- Al darse de alta como reutilizador se solicitará al ayuntamiento inversión territorializada y presupuestos participativos. Si aparecen, se abrirá una ADR para extender `spending` (o crear un contexto nuevo), nunca se parcheará con geocodificación.
- `stage` tiene semántica por fuente: OCDS solo produce `planned` (licitación activa) y `committed` (adjudicación/contrato); `executed` sale de presupuesto (obligaciones y pagos) y de subvenciones. El importe de contratación es adjudicado, no pagado, y así se etiqueta.
- La fase 3 necesita tres adaptadores anti-corrupción distintos (OCDS release package, `{totalCount,result}` de presupuesto con `fecha` `yyyyMMdd`, paginación propia `page`/`pageSize` de subvenciones), todos sin paginación condicional (S0.5).
- `SPEC.md` §4.3 y §4.7 usan `spending` en lugar de `investment`; §9 cierra la decisión.

## Alternativas descartadas

- **`investment`** con propuestas participativas y obras: las fuentes no existen en datos abiertos.
- **`contracting`** solo con OCDS: dejaría fuera la única fuente de gasto ejecutado (presupuesto) y las subvenciones.
- **Geocodificar** títulos o direcciones de las partes para dar territorio a los contratos: adelanta una lectura que los datos no soportan.
- **Un módulo por fuente** (`contracting`, `budget`, `grants`): contradice la regla 9 (no un módulo por dataset) y las tres fuentes comparten el mismo lenguaje (importe, órgano, `stage`).
