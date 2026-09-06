package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;
import es.zaragoza.observatory.catalog.domain.FreshnessSnapshot;

/** Tabla {@code catalog_freshness_snapshot} (V004). */
@Entity
@Table(name = "catalog_freshness_snapshot")
class FreshnessSnapshotEntity {

	@Id
	private UUID id;

	@Column(name = "dataset_source_id", nullable = false)
	private int datasetSourceId;

	@Column(name = "observed_on", nullable = false)
	private LocalDate observedOn;

	@Column(name = "taken_at", nullable = false, columnDefinition = "timestamptz")
	private Instant takenAt;

	@Column(name = "declared_age_days")
	private Integer declaredAgeDays;

	@Column(name = "periodicity_days")
	private Integer periodicityDays;

	@Column(name = "declared_ratio")
	private Double declaredRatio;

	@Enumerated(EnumType.STRING)
	@Column(name = "declared_freshness", nullable = false, columnDefinition = "text")
	private DeclaredFreshness declared;

	@Column(name = "observed_last_change", columnDefinition = "timestamptz")
	private Instant observedLastChange;

	@Column(name = "observed_records")
	private Integer observedRecords;

	@Column(name = "observation_method", columnDefinition = "text")
	private String observationMethod;

	protected FreshnessSnapshotEntity() {
	}

	static FreshnessSnapshotEntity from(FreshnessSnapshot snapshot) {
		var e = new FreshnessSnapshotEntity();
		e.id = snapshot.id();
		e.datasetSourceId = snapshot.datasetSourceId();
		e.observedOn = snapshot.observedOn();
		e.apply(snapshot);
		return e;
	}

	void apply(FreshnessSnapshot snapshot) {
		takenAt = snapshot.takenAt();
		declaredAgeDays = snapshot.declaredAgeDays();
		periodicityDays = snapshot.periodicityDays();
		declaredRatio = snapshot.declaredRatio();
		declared = snapshot.declared();
		observedLastChange = snapshot.observedLastChange();
		observedRecords = snapshot.observedRecords();
		observationMethod = snapshot.observationMethod();
	}

	FreshnessSnapshot toDomain() {
		return new FreshnessSnapshot(id, datasetSourceId, observedOn, takenAt, declaredAgeDays, periodicityDays,
				declaredRatio, declared, observedLastChange, observedRecords, observationMethod);
	}

}
