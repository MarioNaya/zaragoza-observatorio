/**
 * Los cuerpos que devuelve la API del observatorio, escritos contra las respuestas reales y no de memoria
 * (regla 1). El contrato vive en `/v3/api-docs`; estos tipos son su lectura en TypeScript.
 */

/** Cobertura de una columna sobre la ciudad entera. `assigned` es lo que suman las 29 filas, no `total`. */
export interface Coverage {
  total: number;
  withPoint: number;
  assigned: number;
  unassigned: number;
  /** Proporción con punto, 0..1. `null` si no hay registros que medir. */
  pointCoverage: number | null;
}

/** Una columna del cruce, descrita por sí misma (ADR-019 §2). */
export interface MeasureColumn {
  id: MeasureId;
  module: string;
  /** Qué cuenta: `requests`, `premises`, `licences`. Ninguna es intercambiable con otra. */
  unit: string;
  /** Campo del origen sobre el que se aplica la ventana. */
  dateField: string;
  /** Qué significa ese campo, en palabras. */
  dateFieldMeaning: string;
  /** Cuándo se leyó el origen por última vez. No es cuándo cambió el origen (ADR-019 §10). */
  ingestedAt: string | null;
  coverage: Coverage;
}

/** Una fila: la junta con su denominador y el valor de cada medida pedida. */
export interface DistrictRow {
  districtId: number;
  name: string;
  shortName: string;
  padronId: number | null;
  /** Padrón de la junta en `populationYear`; `null` si ese año no existe en la serie. */
  population: number | null;
  populationYear: number | null;
  values: Record<string, number>;
  /** Vacío cuando la junta no tiene padrón del año usado: el hueco se ve, no se rellena. */
  perThousandInhabitants: Record<string, number>;
}

export interface DateWindow {
  from: string | null;
  to: string | null;
}

export interface CrossTab {
  axis: string;
  window: DateWindow;
  denominator: Denominator;
  populationYear: number | null;
  sort: string;
  measures: MeasureColumn[];
  districts: number;
  items: DistrictRow[];
}

export interface PopulationYear {
  year: number;
  population: number;
}

export interface DistrictCard {
  window: DateWindow;
  denominator: Denominator;
  populationYear: number | null;
  measures: MeasureColumn[];
  item: DistrictRow;
  populationSeries: PopulationYear[];
}

/** Sobre del módulo `territory`: sin `ingestedAt` en la raíz, hay uno por medida (ADR-019 §2). */
export interface ApiItem<T> {
  caveats: string[];
  item: T;
}

/** El catálogo es cerrado y crece añadiendo una entrada, nunca abriendo un parámetro (ADR-019 §2). */
export type MeasureId = 'citizen.requests' | 'urban.premises' | 'urban.licences';

export const ALL_MEASURES: readonly MeasureId[] = [
  'citizen.requests',
  'urban.premises',
  'urban.licences',
] as const;

/** Etiquetas de usuario en español (regla 12); los identificadores siguen siendo los de la API. */
export const MEASURE_LABELS: Record<MeasureId, string> = {
  'citizen.requests': 'Quejas y sugerencias',
  'urban.premises': 'Locales con licencia',
  'urban.licences': 'Licencias de actividad',
};

export const UNIT_LABELS: Record<string, string> = {
  requests: 'quejas',
  premises: 'locales',
  licences: 'licencias',
};

export type Denominator = 'population' | 'none';

// --- geo ------------------------------------------------------------------------------------------------

export interface GeoJsonPolygon {
  type: 'Polygon' | 'MultiPolygon';
  coordinates: number[][][] | number[][][][];
}

export interface DistrictFeature {
  type: 'Feature';
  id: number;
  geometry: GeoJsonPolygon;
  properties: {
    id: number;
    name: string;
    shortName: string;
    kind: string;
    padronId: number | null;
  };
}

export interface DistrictBoundaries {
  type: 'FeatureCollection';
  count: number;
  features: DistrictFeature[];
}
