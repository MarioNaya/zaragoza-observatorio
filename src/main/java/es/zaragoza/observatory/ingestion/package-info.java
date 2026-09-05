/**
 * Infraestructura de ingesta (SPEC.md §4.3, §4.5): cliente de la API municipal, jobs, registro de ejecuciones,
 * resiliencia. No conoce ningún dominio: recibe descripciones de qué traer y devuelve payloads crudos.
 */
@ApplicationModule(displayName = "Ingestion", allowedDependencies = { "shared" })
package es.zaragoza.observatory.ingestion;

import org.springframework.modulith.ApplicationModule;
