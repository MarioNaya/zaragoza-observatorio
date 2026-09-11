/**
 * Composición de lectura sobre el eje territorial (SPEC.md §4.8, ADR-019). Pone medidas de varios módulos de
 * dominio en la misma tabla, fila por junta, con su denominador y su cobertura.
 * <p>
 * <b>No tiene estado</b>: ni tablas, ni caché, ni ingesta (regla 3). Todo lo que publica sale de llamar a las
 * superficies públicas de {@code geo}, {@code citizen} y {@code urban} en el momento de la petición.
 * <p>
 * Y <b>nadie depende de él</b>: es el extremo de la cadena. Un módulo de dominio que necesitara algo de aquí
 * sería un módulo de dominio que conoce a otro, que es justo lo que esta figura existe para evitar.
 * <p>
 * {@code spending} no está entre sus dependencias y no es un olvido: ninguna de sus tres fuentes tiene dimensión
 * territorial (S0.2, S0.6, S3.1), así que no hay nada suyo que cruzar por junta y pedirlo es un 400 explicado
 * (ADR-019 §8).
 */
@ApplicationModule(displayName = "Territory", allowedDependencies = { "shared", "geo", "citizen", "urban" })
package es.zaragoza.observatory.territory;

import org.springframework.modulith.ApplicationModule;
