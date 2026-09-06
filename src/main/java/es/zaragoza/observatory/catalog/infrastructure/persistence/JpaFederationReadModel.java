package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetSort;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;
import es.zaragoza.observatory.catalog.domain.FederationReadModel;

/** Lectura de la federación y de su cruce con las fichas por {@code sourceId} (S1.3). */
@Repository
class JpaFederationReadModel implements FederationReadModel {

	private final FederatedDatasetJpaRepository federated;
	private final DatasetJpaRepository datasets;

	JpaFederationReadModel(FederatedDatasetJpaRepository federated, DatasetJpaRepository datasets) {
		this.federated = federated;
		this.datasets = datasets;
	}

	@Override
	@Transactional(readOnly = true)
	public PageOf<FederatedListing> search(FederationFilter filter, FederationSort sort, PageRequest page) {
		Page<FederatedDatasetEntity> result = federated.findAll(specification(filter),
				org.springframework.data.domain.PageRequest.of(page.page(), page.size(), sort(sort)));
		List<Integer> ids = result.getContent().stream().map(FederatedDatasetEntity::getSourceId).toList();
		Set<Integer> inCatalog = ids.isEmpty() ? Set.of()
				: datasets.findAllById(ids).stream().map(DatasetEntity::getSourceId).collect(Collectors.toSet());
		return new PageOf<>(result.getContent().stream()
				.map(e -> new FederatedListing(e.toDomain(), inCatalog.contains(e.getSourceId()))).toList(),
				page.page(), page.size(), result.getTotalElements());
	}

	@Override
	@Transactional(readOnly = true)
	public FederationSummary summary() {
		long total = federated.count();
		long inCatalog = federated.countInCatalog();
		return new FederationSummary(total, inCatalog, total - inCatalog, datasets.count() - datasets.countFederated());
	}

	static Specification<FederatedDatasetEntity> specification(FederationFilter filter) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (filter.inCatalog() != null) {
				Subquery<Integer> catalogIds = query.subquery(Integer.class);
				catalogIds.select(catalogIds.from(DatasetEntity.class).get("sourceId"));
				Predicate in = root.get("sourceId").in(catalogIds);
				predicates.add(filter.inCatalog() ? in : cb.not(in));
			}
			if (filter.text() != null && !filter.text().isBlank()) {
				String pattern = "%" + filter.text().strip().toLowerCase(Locale.ROOT) + "%";
				predicates.add(cb.like(cb.lower(root.get("title")), pattern));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	static Sort sort(FederationSort sort) {
		Sort.Direction direction = sort.direction() == DatasetSort.Direction.DESC ? Sort.Direction.DESC
				: Sort.Direction.ASC;
		String property = switch (sort.field()) {
			case SOURCE_ID -> "sourceId";
			case TITLE -> "title";
		};
		return Sort.by(new Sort.Order(direction, property).nullsLast(), Sort.Order.asc("sourceId"));
	}

}
