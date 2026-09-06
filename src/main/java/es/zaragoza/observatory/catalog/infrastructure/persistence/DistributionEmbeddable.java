package es.zaragoza.observatory.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import es.zaragoza.observatory.catalog.domain.Dataset.Distribution;

@Embeddable
class DistributionEmbeddable {

	@Column(name = "source_id")
	private Integer sourceId;

	@Column(name = "media_type", columnDefinition = "text")
	private String mediaType;

	@Column(name = "access_url", columnDefinition = "text")
	private String accessUrl;

	@Column(name = "download_url", columnDefinition = "text")
	private String downloadUrl;

	@Column(name = "title", columnDefinition = "text")
	private String title;

	protected DistributionEmbeddable() {
	}

	static DistributionEmbeddable from(Distribution distribution) {
		var e = new DistributionEmbeddable();
		e.sourceId = distribution.sourceId();
		e.mediaType = distribution.mediaType();
		e.accessUrl = distribution.accessUrl();
		e.downloadUrl = distribution.downloadUrl();
		e.title = distribution.title();
		return e;
	}

	Distribution toDomain() {
		return new Distribution(sourceId, mediaType, accessUrl, downloadUrl, title);
	}

}
