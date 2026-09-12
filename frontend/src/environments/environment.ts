/**
 * Configuración de compilación (ADR-020 §2). El frontend se publica como sitio estático en otro dominio, así
 * que la URL de la API no se deduce del origen: se declara aquí y viaja en el bundle.
 */
export const environment = {
  /** La instancia desplegada en Railway (docs/despliegue.md). */
  apiBaseUrl: 'https://observatorio-production-ed20.up.railway.app',
};
