-- citizen: quejas y sugerencias del listado de sede (S0.3, S2.2, ADR-011, ADR-012).
--
-- Lo que NO hay aquí es tan importante como lo que hay: **no existe columna para el texto libre**. El origen
-- publica `title`, `description` y `service_notice` sin anonimizar (nombres, firmas y algún DNI), y la ingesta
-- ni siquiera los pide: manda un `fl` de ocho campos y el texto no llega (ADR-012). No es una política de
-- borrado que alguien pueda desactivar, es una ausencia estructural. Tampoco hay `address_string`: ADR-011 §2
-- prohíbe geocodificar por dirección, así que no tendría uso.
--
-- Dos campos territoriales y no uno (ADR-011 §3): `district_id` es la junta **resuelta** por `ST_Contains`
-- sobre los polígonos de `geo_district`, y `district_declared` el nombre que trae el origen **tal cual**, con
-- `district_declared_id` como su casado por la tabla de sinónimos. El declarado nunca sustituye al resuelto: la
-- discrepancia entre ambos es información publicable sobre la calidad del dato.
--
-- `district_id` y `district_declared_id` NO llevan clave ajena a `geo_district` a propósito: son módulos
-- distintos y ninguno accede a las tablas del otro (regla 3). La integridad se sostiene en que el único que
-- escribe ahí es el resolutor de `geo`.

CREATE TABLE citizen_service_request (
    -- `service_request_id` del origen. Los ids no son densos: van de ~317.000 (2013) a ~948.000 (2026).
    source_id            bigint      PRIMARY KEY,
    -- `open` / `closed` del origen, más `UNKNOWN` para cualquier valor no visto todavía (no se descarta el
    -- registro por no reconocer su estado: se marca).
    status               text        NOT NULL,
    -- La taxonomía es heterogénea (`250`, `18014`, `97550336`): se guarda como texto, que es lo que es.
    service_code         text        NOT NULL,
    service_name         text,
    -- Las fechas del origen vienen sin zona y son hora local de Zaragoza (S0.5): se guardan ya convertidas.
    requested_at         timestamptz NOT NULL,
    -- `updated_datetime` tal cual. Solo cuenta como fecha de cierre cuando `status = 'CLOSED'` (S0.3: se
    -- informa al cerrar), pero se guarda siempre porque es la marca de agua de la ingesta de cierres.
    updated_at           timestamptz,
    -- Punto en WGS84, o NULL. Sin punto no hay junta (ADR-011 §2): el registro queda sin asignar y se cuenta.
    lon                  double precision,
    lat                  double precision,
    district_id          integer,
    -- RESOLVED · AMBIGUOUS (los polígonos se solapan en Juslibol) · OUTSIDE · NO_POINT. Cuatro estados, no dos.
    assignment           text        NOT NULL,
    district_declared    text,
    district_declared_id integer,
    first_seen_at        timestamptz NOT NULL,
    last_seen_at         timestamptz NOT NULL,
    CONSTRAINT citizen_service_request_point_complete CHECK ((lon IS NULL) = (lat IS NULL)),
    CONSTRAINT citizen_service_request_assignment_point CHECK (
        (assignment = 'NO_POINT') = (lon IS NULL)),
    CONSTRAINT citizen_service_request_assignment_district CHECK (
        (district_id IS NOT NULL) = (assignment IN ('RESOLVED', 'AMBIGUOUS')))
);

-- Listado por fecha descendente con desempate estable (regla 8) y marca de agua de altas.
CREATE INDEX citizen_service_request_requested_idx
    ON citizen_service_request (requested_at DESC, source_id DESC);
-- Marca de agua de cierres y tiempo de respuesta.
CREATE INDEX citizen_service_request_updated_idx
    ON citizen_service_request (updated_at DESC) WHERE updated_at IS NOT NULL;
CREATE INDEX citizen_service_request_district_idx
    ON citizen_service_request (district_id) WHERE district_id IS NOT NULL;
CREATE INDEX citizen_service_request_service_idx ON citizen_service_request (service_code);
