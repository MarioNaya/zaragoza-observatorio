package es.zaragoza.observatory.spending.infrastructure.zaragoza;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import es.zaragoza.observatory.spending.domain.BudgetLine;
import es.zaragoza.observatory.spending.domain.BudgetSnapshotRead;
import es.zaragoza.observatory.spending.domain.BudgetSource;
import es.zaragoza.observatory.spending.infrastructure.SpendingProperties;

/**
 * Lee una instantánea entera del presupuesto: tres páginas de 500 con <b>{@code sort=id asc}</b> (S3.2 §2 y
 * {@link BudgetListing}). Usa el cliente HTTP común de la aplicación (regla 23), con una pausa entre peticiones
 * por cortesía y sin reintentos: lo que falle hoy se reintenta cuando toque.
 * <p>
 * Cuenta por <b>conceptos distintos</b> y no por filas, que es la lección de S2.2 §10: una página repetida por
 * un cambio de orden se notaría al contar identificadores y no al contar filas. Con {@code sort=id asc} el
 * barrido de las 140 instantáneas no perdió ni repitió una sola fila.
 * <p>
 * Se para cuando ha traído lo que la fuente dijo que había, cuando una página llega corta o cuando una página
 * no aporta ningún concepto nuevo. Esa tercera condición es la red de seguridad contra una paginación que se
 * quede clavada: sin ella, un servidor que devolviera siempre la primera página daría un bucle infinito.
 */
public class BudgetHttpSource implements BudgetSource {

	private static final Logger log = LoggerFactory.getLogger(BudgetHttpSource.class);

	/** Tope de páginas por instantánea. La mayor observada son 1.303 partidas, tres páginas. */
	private static final int MAX_PAGES = 20;

	private final RestClient rest;
	private final BudgetLineJsonTranslator translator;
	private final SpendingProperties properties;
	private final Duration requestDelay;
	private long lastRequestNanos;
	private boolean requested;

	public BudgetHttpSource(RestClient rest, BudgetLineJsonTranslator translator, SpendingProperties properties) {
		this.rest = Objects.requireNonNull(rest);
		this.translator = Objects.requireNonNull(translator);
		this.properties = Objects.requireNonNull(properties);
		this.requestDelay = properties.budget().requestDelay();
	}

	@Override
	public BudgetSnapshotRead read(LocalDate date) {
		int rows = properties.budget().rows();
		var lines = new ArrayList<BudgetLine>();
		var concepts = new LinkedHashSet<String>();
		int reported = -1;
		for (int page = 0; page < MAX_PAGES; page++) {
			int start = page * rows;
			URI uri = pageUrl(date, start, rows);
			String body;
			try {
				pause();
				body = rest.get().uri(uri).retrieve().body(String.class);
			}
			catch (RestClientResponseException ex) {
				HttpStatusCode status = ex.getStatusCode();
				if (status.value() == 404 && page == 0) {
					// El censo la publica y su URL no responde. Es un hecho de la fuente, no un error de ingesta.
					return BudgetSnapshotRead.absent(date);
				}
				log.warn("spending: la instantánea {} respondió {} en start={} ({})", date, status.value(), start,
						uri);
				return BudgetSnapshotRead.unreadable(date);
			}
			catch (RuntimeException ex) {
				log.warn("spending: la instantánea {} no se pudo leer en start={} ({}): {}", date, start, uri,
						ex.toString());
				return BudgetSnapshotRead.unreadable(date);
			}
			if (body == null || body.isBlank()) {
				log.warn("spending: la instantánea {} devolvió un cuerpo vacío en start={} ({})", date, start, uri);
				return BudgetSnapshotRead.unreadable(date);
			}
			List<BudgetLine> pageLines;
			try {
				if (page == 0) {
					reported = translator.totalCount(body);
				}
				pageLines = translator.translate(date, body);
			}
			catch (RuntimeException ex) {
				log.warn("spending: la página start={} de la instantánea {} no se pudo traducir ({}): {}", start,
						date, uri, ex.toString());
				return BudgetSnapshotRead.unreadable(date);
			}
			int added = add(lines, concepts, pageLines);
			if (pageLines.isEmpty()) {
				break;
			}
			if (added == 0) {
				log.warn("spending: la página start={} de la instantánea {} no aportó ninguna partida nueva; se "
						+ "corta el barrido", start, date);
				break;
			}
			if (pageLines.size() < rows || (reported >= 0 && lines.size() >= reported)) {
				break;
			}
		}
		if (lines.isEmpty()) {
			return BudgetSnapshotRead.empty(date);
		}
		return BudgetSnapshotRead.loaded(date, lines, reported);
	}

	/** Añade las partidas cuyo concepto no se haya visto ya en esta instantánea; devuelve cuántas eran. */
	private static int add(List<BudgetLine> lines, Set<String> concepts, List<BudgetLine> page) {
		int added = 0;
		for (BudgetLine line : page) {
			if (concepts.add(line.concept())) {
				lines.add(line);
				added++;
			}
		}
		return added;
	}

	private URI pageUrl(LocalDate date, int start, int rows) {
		return UriComponentsBuilder.fromUri(properties.budget().snapshotUrl(date))
				.queryParam("rows", rows)
				.queryParam("start", start)
				.queryParam(BudgetListing.SORT, BudgetListing.SORT_BY_ID)
				.encode()
				.build()
				.toUri();
	}

	private void pause() {
		if (requested && !requestDelay.isZero()) {
			long remaining = requestDelay.toNanos() - (System.nanoTime() - lastRequestNanos);
			if (remaining > 0) {
				try {
					Thread.sleep(Duration.ofNanos(remaining));
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		requested = true;
		lastRequestNanos = System.nanoTime();
	}

}
