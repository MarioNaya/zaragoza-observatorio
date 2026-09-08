package es.zaragoza.observatory.geo;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Casado de nombres de junta (ADR-011 §5): las fuentes municipales publican el nombre como texto en mayúsculas y
 * sin el prefijo oficial, y unas cuantas variantes no coinciden con ningún nombre de {@code distrito.json}.
 * <p>
 * El casado tiene dos pasos y ese orden importa:
 * <ol>
 * <li>{@link #normalize(String) clave normalizada} —mayúsculas, sin tildes, sin signos, sin el prefijo «Junta
 * Municipal/Vecinal» y sin artículo inicial—, que resuelve 26 de las 30 cadenas distintas observadas;</li>
 * <li><b>tabla explícita de sinónimos</b> para las cuatro que no, cada una con la respuesta real en la que se
 * vio (S2.2). ADR-011 §5: no se añade un sinónimo sin haberlo observado.</li>
 * </ol>
 * Es una instantánea inmutable: se construye con las juntas que hay en ese momento y se usa para un lote entero
 * (una página de 500 quejas), no una consulta por registro.
 * <p>
 * Lo que <b>no</b> hace: adivinar. Un nombre que no case devuelve vacío y el registro se queda sin junta
 * declarada, que es un dato publicable; no se busca el parecido más próximo (ADR-011 §2, el mismo argumento por
 * el que no se geocodifica por dirección).
 */
public final class DistrictNames {

	/**
	 * Variantes observadas que la clave normalizada no resuelve, con la evidencia de cada una
	 * (docs/spikes/S2.2-quejas-ingesta.md §7, sobre 9.000 registros de {@code quejas-sugerencias/list.json}):
	 * <ul>
	 * <li>{@code DISTRITO SUR} → 30 «Junta Municipal Sur»: la fuente antepone «DISTRITO»;</li>
	 * <li>{@code SAN JUAN DE MOZARRIFAR} → 25 «Junta Vecinal San Juan Mozarrifar»: la fuente añade «DE»;</li>
	 * <li>{@code TORRECILLA} → 26 «Junta Vecinal Torrecilla de Valmadrid»: la fuente trunca el nombre;</li>
	 * <li>{@code CASCO HISTO RICO} → 3 «Junta Municipal Casco Histórico»: el origen intercala un carácter de
	 * control U+0093 dentro de la palabra, que {@link #normalize} convierte en espacio. El mismo defecto de
	 * codificación que S2.1 vio en {@code portalero/v2}.</li>
	 * </ul>
	 * La clave es ya la forma normalizada, para que la comparación sea una sola operación.
	 */
	static final Map<String, Integer> SYNONYMS = Map.of(
			"DISTRITO SUR", 30,
			"SAN JUAN DE MOZARRIFAR", 25,
			"TORRECILLA", 26,
			"CASCO HISTO RICO", 3);

	private final Map<String, Integer> byNormalizedName;

	private DistrictNames(Map<String, Integer> byNormalizedName) {
		this.byNormalizedName = byNormalizedName;
	}

	/**
	 * Índice a partir de los nombres oficiales, id por nombre. Los sinónimos se añaden después y <b>no</b> pisan
	 * un nombre oficial: si algún día la fuente oficial pasara a llamarse como un sinónimo, manda la oficial.
	 */
	public static DistrictNames of(Map<Integer, String> officialNamesById) {
		var index = new LinkedHashMap<String, Integer>();
		officialNamesById.forEach((id, name) -> index.put(normalize(name), id));
		SYNONYMS.forEach(index::putIfAbsent);
		return new DistrictNames(Map.copyOf(index));
	}

	/** Junta que corresponde al nombre que declara una fuente, o vacío si no casa con ninguna. */
	public Optional<Integer> resolve(String declaredName) {
		if (declaredName == null || declaredName.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(byNormalizedName.get(normalize(declaredName)));
	}

	/**
	 * Clave de comparación: mayúsculas, sin tildes ni diacríticos, sin el prefijo «Junta Municipal/Vecinal», sin
	 * artículo inicial y con cualquier otro carácter convertido en espacio. Lo último es deliberado: así un
	 * carácter de control incrustado no revienta la comparación, solo parte la palabra, y el sinónimo
	 * correspondiente lo recoge.
	 */
	public static String normalize(String name) {
		if (name == null) {
			return "";
		}
		String value = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		value = value.toUpperCase(Locale.ROOT);
		value = value.replaceAll("[^A-Z0-9 ]", " ");
		value = value.replaceAll("\\b(JUNTA)?\\s*(MUNICIPAL|VECINAL)\\b", " ");
		value = value.replaceAll("^\\s*(EL|LA|LOS|LAS)\\b", " ");
		return value.replaceAll("\\s+", " ").trim();
	}

	/** Número de nombres indexados (oficiales más sinónimos). Para logs y tests. */
	public int size() {
		return byNormalizedName.size();
	}

}
