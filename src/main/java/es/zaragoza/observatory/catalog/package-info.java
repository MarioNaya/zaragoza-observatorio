/**
 * Dominio del catálogo municipal (SPEC.md §4.3, §4.6): datasets, frescura e histórico.
 * Depende de ingestion (a través de sus puertos) y de shared.
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = { "shared", "ingestion" })
package es.zaragoza.observatory.catalog;

import org.springframework.modulith.ApplicationModule;
