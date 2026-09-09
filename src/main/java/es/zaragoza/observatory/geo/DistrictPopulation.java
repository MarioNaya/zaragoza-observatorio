package es.zaragoza.observatory.geo;

/**
 * El padrón de una junta en un año concreto, para quien agrega por territorio <b>y por tiempo</b>. Es el
 * denominador que exige la regla 7 cuando la serie tiene más de un año: usar siempre el padrón más reciente para
 * todos los años convierte una serie de tasas en una mezcla de dos cosas distintas.
 * <p>
 * <b>La serie no es continua</b>: el ayuntamiento publica 2020, 2021, 2022 y 2024, y no 2023 (S2.1, ADR-011).
 * Quien agregue por año se encontrará años sin denominador, y eso es un hecho de la fuente que se publica, no un
 * hueco que se rellene interpolando.
 *
 * @param districtId junta ({@code distrito.id})
 * @param year año del padrón
 * @param population población total de esa junta en ese año
 */
public record DistrictPopulation(int districtId, int year, int population) {
}
