package es.zaragoza.observatory.ingestion.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;

/** Retención de {@code raw_payload} (SPEC.md §4.5, §9): borra las páginas más antiguas que la retención. */
public class PurgeRawPayloads {

	private static final Logger log = LoggerFactory.getLogger(PurgeRawPayloads.class);

	private final RawPayloadStore payloads;
	private final Clock clock;
	private final Duration retention;

	public PurgeRawPayloads(RawPayloadStore payloads, Clock clock, Duration retention) {
		this.payloads = Objects.requireNonNull(payloads);
		this.clock = Objects.requireNonNull(clock);
		this.retention = Objects.requireNonNull(retention);
		if (retention.isNegative()) {
			throw new IllegalArgumentException("retention must not be negative");
		}
	}

	public int purge() {
		int deleted = payloads.purgeFetchedBefore(clock.instant().minus(retention));
		if (deleted > 0) {
			log.info("purged {} raw payloads older than {}", deleted, retention);
		}
		return deleted;
	}

}
