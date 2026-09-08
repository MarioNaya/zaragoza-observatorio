/**
 * Ciudadanía (SPEC.md §4.3, §4.6): las quejas y sugerencias del listado de sede, con su junta resuelta por
 * geometría (ADR-011) y <b>sin el texto libre</b> que el origen publica sin anonimizar (ADR-012).
 * <p>
 * Depende de {@code geo} porque {@code geo} es shared kernel: le pide la junta de un punto y el casado del
 * nombre declarado. No al revés.
 */
@ApplicationModule(displayName = "Citizen", allowedDependencies = { "shared", "ingestion", "geo" })
package es.zaragoza.observatory.citizen;

import org.springframework.modulith.ApplicationModule;
