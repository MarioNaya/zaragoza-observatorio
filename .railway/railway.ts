// Infraestructura del Observatorio en Railway (ADR-008).
//
// Este fichero NO contiene ningún secreto y está pensado para versionarse: la contraseña de la
// base de datos la generó y la custodia Railway, aquí aparece como `preserve()` («conserva el valor
// que ya está en Railway»), y la aplicación la recibe como referencia con `ref(...)`. No usar nunca
// `railway config pull --include-variables`: descifra los valores y los escribe en este fichero.
//
//   railway config plan    previsualiza sin tocar nada
//   railway config apply   aplica tras confirmar
//
// Ojo: en IaC lo que no está aquí se borra, incluidas las variables. Por eso se declaran todas las
// que Railway puso en el servicio de base de datos, aunque la aplicación no las use.
//
// Por qué la base de datos es un `service` con imagen y no un `database(...)`: el ayudante
// `database()` acepta una imagen propia, pero al aplicarla Railway convierte el recurso en servicio,
// y entonces el fichero —que lo declaraba como base de datos— quiere borrarlo y recrearlo con la
// imagen por defecto. Es un bucle. Declararlo como servicio describe lo que Railway hace de verdad.

import { defineRailway, github, image, preserve, project, ref, service, volume } from "railway/iac";

export default defineRailway(() => {
  const postgisVolume = volume("postgis-volume", {
    alerts: { usage: { "80": {}, "95": {}, "100": {} } },
    allowOnlineResize: true,
    region: "europe-west4-drams3a",
    sizeMB: 5000,
  });

  // PostGIS: la misma imagen que `compose.yaml` y Testcontainers. El Postgres por defecto de
  // Railway no trae la extensión, y sin ella `V001__postgis_extension.sql` aborta el arranque.
  const postgis = service("postgis", {
    source: image("postgis/postgis:17-3.5"),
    replicas: { "europe-west4-drams3a": 1 },
    deploy: { requiredMountPath: "/var/lib/postgresql/data" },
    volumeMounts: { "/var/lib/postgresql/data": postgisVolume },
    env: {
      DATABASE_URL: preserve(),
      // Directorio de datos propio, y no el `pgdata` heredado: al crear el servicio, Railway lo
      // inicializó con su Postgres 18 por defecto, y PostgreSQL 17 no arranca sobre ese clúster
      // («unrecognized configuration parameter "autovacuum_worker_slots"»). Apuntando a un
      // directorio nuevo, la imagen 17 inicializa el suyo y el anterior se queda intacto en el
      // volumen; no se borra nada. Se puede limpiar cuando conste que no hace falta.
      PGDATA: "/var/lib/postgresql/data/pgdata-postgis17",
      PGDATABASE: preserve(),
      PGHOST: preserve(),
      PGPASSWORD: preserve(),
      PGPORT: preserve(),
      PGUSER: preserve(),
      POSTGRES_DB: preserve(),
      POSTGRES_PASSWORD: preserve(),
      POSTGRES_USER: preserve(),
      RAILWAY_DEPLOYMENT_DRAINING_SECONDS: preserve(),
      SSL_CERT_DAYS: preserve(),
    },
  });

  // La aplicación se construye con el `Dockerfile` de la raíz, que Railway detecta solo.
  const observatorio = service("observatorio", {
    source: github("MarioNaya/zaragoza-observatorio", { branch: "main", checkSuites: false }),
    healthcheck: "/actuator/health",
    // El arranque incluye Flyway; en el primer despliegue aplica las siete migraciones.
    healthcheckTimeout: 300,
    // Una sola réplica: sin ShedLock, dos instancias duplicarían el planificador de ingesta
    // y las ingestas se pisarían entre sí (ADR-004).
    replicas: { "europe-west4-drams3a": 1 },
    // Las cinco variables que espera el perfil prod, por referencia al servicio de base de datos.
    env: {
      PGHOST: ref(postgis, "PGHOST"),
      PGPORT: ref(postgis, "PGPORT"),
      PGDATABASE: ref(postgis, "PGDATABASE"),
      PGUSER: ref(postgis, "PGUSER"),
      PGPASSWORD: ref(postgis, "PGPASSWORD"),
    },
  });

  return project("zaragoza-observatorio", {
    resources: [postgisVolume, postgis, observatorio],
  });
});
