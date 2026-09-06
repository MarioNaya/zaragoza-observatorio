package es.zaragoza.observatory.ingestion;

/** Estado de una ejecución de ingesta (SPEC.md §4.5 paso 5). */
public enum RunStatus {
	RUNNING, SUCCEEDED, FAILED
}
