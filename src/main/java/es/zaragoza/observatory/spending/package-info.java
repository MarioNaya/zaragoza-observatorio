/**
 * Gasto público municipal (ADR-003): hoy, la contratación pública en formato OCDS (S3.1, ADR-017); después, el
 * presupuesto con sus instantáneas de ejecución y las subvenciones.
 * <p>
 * <b>No depende de {@code geo}, y no es un olvido</b>: ninguna fuente de gasto municipal publicada tiene
 * dimensión territorial. En OCDS está medido sobre la fuente entera —cero caminos de localización en 184
 * caminos distintos y 5.622 documentos—, así que aquí no hay junta, ni punto, ni parámetro territorial, y
 * ninguna respuesta admite uno. La ausencia se publica en {@code caveats} en vez de dejar que se note.
 * <p>
 * Tampoco publica dinero pagado. OCDS no trae ni previsión ({@code planning}) ni ejecución
 * ({@code implementation}): lo que hay es licitado y adjudicado, y cada respuesta lo dice.
 */
@ApplicationModule(displayName = "Spending", allowedDependencies = { "shared", "ingestion" })
package es.zaragoza.observatory.spending;

import org.springframework.modulith.ApplicationModule;
