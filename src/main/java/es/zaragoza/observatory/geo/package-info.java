/**
 * Territorio (SPEC.md §4.3, §4.6): las 29 juntas municipales y vecinales con su geometría, el padrón por junta y
 * año, y la resolución punto → junta (ADR-011). Shared kernel: lo consumen los módulos de dominio con datos
 * territoriales; él no depende de ninguno.
 */
@ApplicationModule(displayName = "Geo", allowedDependencies = { "shared", "ingestion" })
package es.zaragoza.observatory.geo;

import org.springframework.modulith.ApplicationModule;
