package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.ingestion.domain.RawPayload;
import es.zaragoza.observatory.ingestion.domain.RawPayloadStore;
import es.zaragoza.observatory.shared.IngestionRunId;

@Repository
class JpaRawPayloadStore implements RawPayloadStore {

	private final RawPayloadJpaRepository jpa;

	JpaRawPayloadStore(RawPayloadJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional
	public void store(RawPayload payload) {
		jpa.save(RawPayloadEntity.from(payload));
	}

	@Override
	@Transactional(readOnly = true)
	public List<RawPayload> findByRun(IngestionRunId run) {
		return jpa.findByRunIdOrderByPageNumberAsc(run.value()).stream().map(RawPayloadEntity::toDomain).toList();
	}

	@Override
	@Transactional
	public int purgeFetchedBefore(Instant threshold) {
		return jpa.deleteByFetchedAtBefore(threshold);
	}

}
