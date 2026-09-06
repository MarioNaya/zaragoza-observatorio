// Infraestructura del Observatorio en Railway (ADR-008).
//
// Este fichero NO contiene ningún secreto y está pensado para versionarse: la contraseña de la
// base de datos la genera y la custodia Railway, y aquí solo hay referencias (`db.env.PGPASSWORD`),
// igual que `application.yaml` solo lee `${PGPASSWORD}` del entorno. No usar nunca
// `railway config pull --include-variables`: descifra los valores y los escribe en este fichero.
//
//   railway config plan    previsualiza sin tocar nada
//   railway config apply   aplica tras confirmar
//
// Ojo: en IaC lo que no está aquí se borra. Cualquier servicio nuevo se añade a este fichero.

import { database, defineRailway, github, project, service } from "railway/iac";

export default defineRailway(() => {
	// Postgres con la imagen PostGIS, la misma que `compose.yaml` y Testcontainers. El Postgres por
	// defecto de Railway no trae la extensión, y sin ella `V001__postgis_extension.sql` no arranca.
	const db = database("postgis", "postgres", {
		image: "postgis/postgis:17-3.5",
	});

	// La aplicación se construye con el Dockerfile de la raíz, que Railway detecta solo.
	const app = service("observatorio", {
		source: github("MarioNaya/zaragoza-observatorio", { branch: "main" }),
		healthcheckPath: "/actuator/health",
		// El arranque incluye Flyway; en el primer despliegue aplica las siete migraciones.
		healthcheckTimeout: 300,
		// Una sola réplica: sin ShedLock, dos instancias duplicarían el planificador de ingesta
		// y las ingestas se pisarían entre sí (ADR-004).
		replicas: 1,
		// Las cinco variables que espera el perfil prod, por referencia al servicio de base de datos.
		env: {
			PGHOST: db.env.PGHOST,
			PGPORT: db.env.PGPORT,
			PGDATABASE: db.env.PGDATABASE,
			PGUSER: db.env.PGUSER,
			PGPASSWORD: db.env.PGPASSWORD,
		},
	});

	return project("zaragoza-observatorio", {
		resources: [db, app],
	});
});
