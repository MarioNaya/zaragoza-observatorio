-- Registro de publicación de eventos de Spring Modulith (ADR-004).
-- Copia literal de org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql
-- del artefacto spring-modulith-events-jdbc 2.1.1 (estructura v2, la predeterminada).
-- La inicialización automática del starter queda desactivada; Flyway es el único dueño del esquema.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
