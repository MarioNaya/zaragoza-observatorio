package es.zaragoza.observatory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Misma imagen que compose.yaml: PostGIS real, nunca H2 (SPEC.md §6). */
	static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
			.asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGIS_IMAGE);
	}

}
