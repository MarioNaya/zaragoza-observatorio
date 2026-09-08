package es.zaragoza.observatory.geo;

/**
 * Lo que otro módulo necesita saber de una junta para agregar sobre ella (regla 7: toda agregación territorial
 * expone su denominador). No es la entidad de {@code geo}: no lleva geometría ni la serie completa de padrón.
 * <p>
 * {@code populationYear} nunca es implícito. La serie del padrón tiene 2020, 2021, 2022 y 2024 —<b>falta
 * 2023</b>, S2.1—, así que el año más reciente de una junta puede no ser el mismo que el de otra y una
 * normalización que no diga qué año usa está mintiendo por omisión.
 *
 * @param id {@code distrito.id}
 * @param name nombre oficial con su prefijo
 * @param shortName nombre sin «Junta Municipal»/«Junta Vecinal», para etiquetas
 * @param padronId numeración de SOCIO24, {@code null} si no se ha leído el detalle
 * @param population padrón total del año más reciente disponible, {@code null} si no hay
 * @param populationYear año de ese padrón, {@code null} si no hay
 */
public record DistrictSummary(int id, String name, String shortName, Integer padronId, Integer population,
		Integer populationYear) {

	public DistrictSummary {
		if ((population == null) != (populationYear == null)) {
			throw new IllegalArgumentException("population and populationYear go together (district " + id + ")");
		}
	}

}
