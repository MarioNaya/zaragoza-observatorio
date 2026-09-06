package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.DatasetReadModel;
import es.zaragoza.observatory.catalog.domain.DeclaredFreshness;

/** Lectura del monitor con Specifications de Spring Data: filtros combinables, orden explícito y paginación. */
@Repository
class JpaDatasetReadModel implements DatasetReadModel {

	private final DatasetJpaRepository jpa;

	JpaDatasetReadModel(DatasetJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	@Transactional(readOnly = true)
	public PageOf<DatasetListing> search(DatasetFilter filter, DatasetSort sort, PageRequest page) {
		Page<DatasetEntity> result = jpa.findAll(specification(filter),
				org.springframework.data.domain.PageRequest.of(page.page(), page.size(), sort(sort)));
		return new PageOf<>(result.getContent().stream().map(DatasetEntity::toListing).toList(), page.page(),
				page.size(), result.getTotalElements());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<DatasetListing> find(int sourceId) {
		return jpa.findById(sourceId).map(DatasetEntity::toListing);
	}

	@Override
	@Transactional(readOnly = true)
	public CatalogSummary summary() {
		Map<DeclaredFreshness, Long> byFreshness = new EnumMap<>(DeclaredFreshness.class);
		for (DeclaredFreshness f : DeclaredFreshness.values()) {
			byFreshness.put(f, 0L);
		}
		long withoutSnapshot = 0;
		for (Object[] row : jpa.countByLatestFreshness()) {
			if (row[0] == null) {
				withoutSnapshot += (Long) row[1];
			}
			else {
				byFreshness.put((DeclaredFreshness) row[0], (Long) row[1]);
			}
		}
		Map<String, Long> byPeriodicity = new LinkedHashMap<>();
		jpa.countByDeclaredPeriodicity().stream()
				.sorted((a, b) -> Long.compare((Long) b[1], (Long) a[1]))
				.forEach(row -> byPeriodicity.put(row[0] == null ? DatasetFilter.UNDECLARED : (String) row[0],
						(Long) row[1]));
		return new CatalogSummary(jpa.count(), byFreshness, byPeriodicity, jpa.countByHasApiTrue(),
				jpa.countByOpenTrue(), jpa.countByExplorableTrue(), jpa.countByHasGeoTrue(), jpa.latestSnapshotOn(),
				withoutSnapshot);
	}

	static Specification<DatasetEntity> specification(DatasetFilter filter) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (filter.periodicity() != null) {
				predicates.add(DatasetFilter.UNDECLARED.equals(filter.periodicity())
						? cb.isNull(root.get("declaredPeriodicity"))
						: cb.equal(root.get("declaredPeriodicity"), filter.periodicity()));
			}
			if (filter.publicationStatus() != null) {
				predicates.add(cb.equal(root.get("publicationStatus"), filter.publicationStatus()));
			}
			if (filter.hasGeo() != null) {
				predicates.add(cb.equal(root.get("hasGeo"), filter.hasGeo()));
			}
			if (filter.open() != null) {
				predicates.add(cb.equal(root.get("open"), filter.open()));
			}
			if (filter.hasApi() != null) {
				predicates.add(cb.equal(root.get("hasApi"), filter.hasApi()));
			}
			if (filter.freshness() != null) {
				predicates.add(cb.equal(root.get("latestFreshness"), filter.freshness()));
			}
			if (filter.text() != null && !filter.text().isBlank()) {
				String pattern = "%" + filter.text().strip().toLowerCase(Locale.ROOT) + "%";
				predicates.add(cb.like(cb.lower(root.get("title")), pattern));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	static Sort sort(DatasetSort sort) {
		Sort.Direction direction = sort.direction() == DatasetSort.Direction.DESC ? Sort.Direction.DESC
				: Sort.Direction.ASC;
		String property = switch (sort.field()) {
			case TITLE -> "title";
			case SOURCE_ID -> "sourceId";
			case ISSUED -> "issued";
			case DECLARED_MODIFIED -> "declaredModified";
			case METADATA_UPDATED -> "metadataUpdated";
			case DECLARED_RATIO -> "latestRatio";
		};
		Sort.Order primary = new Sort.Order(direction, property).nullsLast();
		return Sort.by(primary, Sort.Order.asc("sourceId"));
	}

}
