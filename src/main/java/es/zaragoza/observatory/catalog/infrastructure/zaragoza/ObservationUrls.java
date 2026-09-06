package es.zaragoza.observatory.catalog.infrastructure.zaragoza;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import es.zaragoza.observatory.catalog.domain.Dataset;
import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;

/**
 * Reglas verificadas en S1.1 para convertir las distribuciones de {@code formato[]} en peticiones de observación
 * (docs/spikes/S1.1-frescura-observada.md, «Método» y recomendación 2). Nada aquí se ha escrito de memoria:
 * <ul>
 * <li>{@code application/api} trae el endpoint en {@code downloadURL}, relativo a {@code www.zaragoza.es} y sin
 * extensión: se añade {@code .json} (S0.5) y {@code rows=1};</li>
 * <li>las distribuciones {@code application/json} bajo {@code /sede/servicio/} o {@code /api/recurso/} son el
 * mismo tipo de endpoint; se prefiere {@code .json} (con {@code totalCount}) a {@code .geojson};</li>
 * <li>los ficheros son URL fuera de la sede con extensión de fichero; responden a {@code HEAD};</li>
 * <li>WFS: {@code GetFeature&typeNames=<wfsFeatureName>&resultType=hits} sobre la URL sin query string;</li>
 * <li>{@code srsname=wgs84} solo en fichas con {@code geo=S}; {@code &amp;} aparece sin decodificar en el catálogo.</li>
 * </ul>
 */
final class ObservationUrls {

	static final String HOST = "https://www.zaragoza.es";

	/** Campos de fecha admitidos para {@code sort desc}, por preferencia (S1.1 recomendación 1). */
	static final List<String> DATE_FIELDS = List.of("lastUpdated", "modified", "updated_datetime",
			"requested_datetime", "publicationDate", "fechaRegistro", "pubDate", "creationDate", "fechaAlta",
			"fecha");

	private static final Pattern FILE_EXTENSION = Pattern.compile(
			"\\.(xlsx?|ods|csv|zip|pdf|gml|xml|kml|kmz|json|geojson|txt|rar|7z|shp|rdf|n3|ttl|dxf|dwg)$",
			Pattern.CASE_INSENSITIVE);

	private ObservationUrls() {
	}

	/** Endpoints de la API: {@code application/api} primero, después JSON de la sede ({@code .json} antes que {@code .geojson}). */
	static List<Distribution> apiCandidates(Dataset dataset) {
		List<Distribution> api = new ArrayList<>();
		List<Distribution> json = new ArrayList<>();
		List<Distribution> geojson = new ArrayList<>();
		for (Distribution d : dataset.distributions()) {
			if (d.isApi()) {
				if (!normalize(d.downloadUrl()).isEmpty()) {
					api.add(d);
				}
			}
			else if (mediaType(d).contains("json") && isSedeApiUrl(url(d))) {
				String path = path(url(d)).toLowerCase(Locale.ROOT);
				if (path.endsWith(".json")) {
					json.add(d);
				}
				else if (path.endsWith(".geojson")) {
					geojson.add(d);
				}
			}
		}
		api.addAll(json);
		api.addAll(geojson);
		return api;
	}

	/** Ficheros descargables: fuera de la sede y con extensión de fichero. */
	static List<Distribution> fileCandidates(Dataset dataset) {
		List<Distribution> files = new ArrayList<>();
		for (Distribution d : dataset.distributions()) {
			String mediaType = mediaType(d);
			String url = url(d);
			if (!d.isApi() && !mediaType.contains("wfs") && !mediaType.contains("wms") && !url.isEmpty()
					&& !isSedeApiUrl(url) && FILE_EXTENSION.matcher(path(url)).find()) {
				files.add(d);
			}
		}
		return files;
	}

	/** Capas WFS con nombre de feature. */
	static List<Distribution> wfsCandidates(Dataset dataset) {
		List<Distribution> layers = new ArrayList<>();
		for (Distribution d : dataset.distributions()) {
			if (mediaType(d).contains("wfs") && d.wfsFeatureName() != null && !d.wfsFeatureName().isBlank()
					&& !url(d).isEmpty()) {
				layers.add(d);
			}
		}
		return layers;
	}

	/** {@code GET <endpoint>.json?…&rows=1[&srsname=wgs84]}, conservando la query string original salvo paginación y orden. */
	static URI apiUrl(Distribution distribution, boolean geo) {
		String raw = distribution.isApi() ? normalize(distribution.downloadUrl()) : url(distribution);
		String path = path(raw);
		String lower = path.toLowerCase(Locale.ROOT);
		if (!lower.endsWith(".json") && !lower.endsWith(".geojson")) {
			path = path + ".json";
		}
		List<String> params = new ArrayList<>();
		String query = query(raw);
		if (!query.isEmpty()) {
			for (String p : query.split("&")) {
				if (!p.isEmpty() && !p.startsWith("rows=") && !p.startsWith("start=") && !p.startsWith("sort=")) {
					params.add(p);
				}
			}
		}
		params.add("rows=1");
		if (geo && params.stream().noneMatch(p -> p.startsWith("srsname="))) {
			params.add("srsname=wgs84");
		}
		return URI.create(path + "?" + String.join("&", params));
	}

	static URI sortedUrl(URI apiUrl, String field) {
		return URI.create(apiUrl + "&sort=" + encode(field + " desc"));
	}

	static URI fileUrl(Distribution distribution) {
		return URI.create(url(distribution));
	}

	static URI wfsHitsUrl(Distribution distribution) {
		return URI.create(path(url(distribution)) + "?service=WFS&version=2.0.0&request=GetFeature&typeNames="
				+ encode(distribution.wfsFeatureName()) + "&resultType=hits");
	}

	/** URL de la distribución: {@code downloadURL} si lo hay, si no {@code accessURL}; normalizada. */
	static String url(Distribution distribution) {
		String download = normalize(distribution.downloadUrl());
		return download.isEmpty() ? normalize(distribution.accessUrl()) : download;
	}

	static String normalize(String url) {
		if (url == null || url.isBlank()) {
			return "";
		}
		String u = url.replace("&amp;", "&").strip();
		return u.startsWith("/") ? HOST + u : u;
	}

	static boolean isSedeApiUrl(String url) {
		return url.contains("/sede/servicio/") || url.contains("/api/recurso/");
	}

	static String path(String url) {
		int q = url.indexOf('?');
		return q < 0 ? url : url.substring(0, q);
	}

	static String query(String url) {
		int q = url.indexOf('?');
		return q < 0 ? "" : url.substring(q + 1);
	}

	private static String mediaType(Distribution d) {
		return d.mediaType() == null ? "" : d.mediaType().toLowerCase(Locale.ROOT);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
