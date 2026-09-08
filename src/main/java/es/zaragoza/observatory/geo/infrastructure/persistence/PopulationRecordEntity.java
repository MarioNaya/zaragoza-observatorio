package es.zaragoza.observatory.geo.infrastructure.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import es.zaragoza.observatory.geo.domain.PopulationRecord;

/** Tabla {@code geo_population_record} (V008): el padrón de una junta en un año, sin índices derivados. */
@Entity
@Table(name = "geo_population_record")
@IdClass(PopulationRecordEntity.Key.class)
class PopulationRecordEntity {

	@Id
	@Column(name = "district_id")
	private Integer districtId;

	@Id
	@Column(name = "year")
	private Integer year;

	@Column(name = "total")
	private Integer total;

	@Column(name = "spaniards")
	private Integer spaniards;

	@Column(name = "foreigners")
	private Integer foreigners;

	@Column(name = "under_16")
	private Integer under16;

	@Column(name = "under_18")
	private Integer under18;

	@Column(name = "households")
	private Integer households;

	@Column(name = "area_km2")
	private Double areaKm2;

	@Column(name = "ingested_at", nullable = false, columnDefinition = "timestamptz")
	private Instant ingestedAt;

	protected PopulationRecordEntity() {
	}

	static PopulationRecordEntity insert(PopulationRecord record) {
		var entity = new PopulationRecordEntity();
		entity.districtId = record.districtId();
		entity.year = record.year();
		entity.apply(record);
		return entity;
	}

	void apply(PopulationRecord record) {
		total = record.total();
		spaniards = record.spaniards();
		foreigners = record.foreigners();
		under16 = record.under16();
		under18 = record.under18();
		households = record.households();
		areaKm2 = record.areaKm2();
		ingestedAt = record.ingestedAt();
	}

	PopulationRecord toDomain() {
		return new PopulationRecord(districtId, year, total, spaniards, foreigners, under16, under18, households,
				areaKm2, ingestedAt);
	}

	/** Clave compuesta (junta, año). */
	static class Key implements Serializable {

		private Integer districtId;
		private Integer year;

		Key() {
		}

		Key(Integer districtId, Integer year) {
			this.districtId = districtId;
			this.year = year;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && Objects.equals(districtId, key.districtId)
					&& Objects.equals(year, key.year);
		}

		@Override
		public int hashCode() {
			return Objects.hash(districtId, year);
		}
	}

}
