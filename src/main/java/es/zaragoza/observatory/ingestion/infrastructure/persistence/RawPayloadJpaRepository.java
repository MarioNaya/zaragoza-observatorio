package es.zaragoza.observatory.ingestion.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RawPayloadJpaRepository extends JpaRepository<RawPayloadEntity, UUID> {

	List<RawPayloadEntity> findByRunIdOrderByPageNumberAsc(UUID runId);

	@Modifying
	@Query("delete from RawPayloadEntity p where p.fetchedAt < :threshold")
	int deleteByFetchedAtBefore(@Param("threshold") Instant threshold);

}
