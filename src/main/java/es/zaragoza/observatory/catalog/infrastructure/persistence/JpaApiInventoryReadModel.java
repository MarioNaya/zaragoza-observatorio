package es.zaragoza.observatory.catalog.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import es.zaragoza.observatory.catalog.domain.ApiEndpoint;
import es.zaragoza.observatory.catalog.domain.ApiInventoryReadModel;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.DatasetSort;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageOf;
import es.zaragoza.observatory.catalog.domain.DatasetReadModel.PageRequest;

/** Lectura del inventario de endpoints y de su cruce con las fichas por {@code apiTag} (S1.2). */
@Repository
class JpaApiInventoryReadModel implements ApiInventoryReadModel {

	private final ApiEndpointJpaRepository endpoints;
	private final DatasetJpaRepository datasets;

	JpaApiInventoryReadModel(ApiEndpointJpaRepository endpoints, DatasetJpaRepository datasets) {
		this.endpoints = endpoints;
		this.datasets = datasets;
	}

	@Override
	@Transactional(readOnly = true)
	public PageOf<ApiEndpoint> search(EndpointFilter filter, EndpointSort sort, PageRequest page) {
		Page<ApiEndpointEntity> result = endpoints.findAll(specification(filter),
				org.springframework.data.domain.PageRequest.of(page.page(), page.size(), sort(sort)));
		return new PageOf<>(result.getContent().stream().map(ApiEndpointEntity::toDomain).toList(), page.page(),
				page.size(), result.getTotalElements());
	}

	@Override
	@Transactional(readOnly = true)
	public List<TagSummary> tags() {
		Map<String, Long> counts = new TreeMap<>();
		for (Object[] row : endpoints.countByTag()) {
			counts.put((String) row[0], (Long) row[1]);
		}
		Map<String, List<DatasetRef>> byTag = new TreeMap<>();
		for (Object[] row : datasets.findApiTagged()) {
			byTag.computeIfAbsent((String) row[2], k -> new ArrayList<>())
					.add(new DatasetRef((Integer) row[0], (String) row[1]));
		}
		Map<String, TagSummary> union = new TreeMap<>();
		counts.forEach((tag, n) -> union.put(tag, new TagSummary(tag, n, byTag.getOrDefault(tag, List.of()))));
		byTag.forEach((tag, refs) -> union.putIfAbsent(tag, new TagSummary(tag, 0, refs)));
		return List.copyOf(union.values());
	}

	@Override
	@Transactional(readOnly = true)
	public InventorySummary summary() {
		return new InventorySummary(endpoints.count(), endpoints.countDistinctTags(), datasets.countByApiTagIsNotNull(),
				datasets.countWithDocumentedApiTag(), endpoints.countTagsWithoutDataset());
	}

	static Specification<ApiEndpointEntity> specification(EndpointFilter filter) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (filter.tag() != null) {
				predicates.add(cb.equal(root.get("tag"), filter.tag()));
			}
			if (filter.text() != null && !filter.text().isBlank()) {
				String pattern = "%" + filter.text().strip().toLowerCase(Locale.ROOT) + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("path")), pattern),
						cb.like(cb.lower(root.get("summary")), pattern)));
			}
			if (filter.templated() != null) {
				predicates.add(filter.templated() ? cb.like(root.get("path"), "%{%")
						: cb.notLike(root.get("path"), "%{%"));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	static Sort sort(EndpointSort sort) {
		Sort.Direction direction = sort.direction() == DatasetSort.Direction.DESC ? Sort.Direction.DESC
				: Sort.Direction.ASC;
		String property = switch (sort.field()) {
			case ORDINAL -> "ordinal";
			case PATH -> "path";
			case TAG -> "tag";
		};
		return Sort.by(new Sort.Order(direction, property), Sort.Order.asc("ordinal"));
	}

}
