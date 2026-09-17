/**
 * Los cuerpos que devuelve la API, escritos contra las respuestas reales de la instancia y no de memoria
 * (regla 1). El contrato vive en `/v3/api-docs`; esto es su lectura en TypeScript.
 *
 * **Un tipo de aquí puede declarar menos campos que la respuesta, nunca otros ni con otra nulabilidad.** Lo
 * primero es elegir qué se usa; lo segundo es una mentira que el compilador no puede ver, y ya costó tres
 * columnas vacías en el catálogo —`observationMethod` por `latestObservationMethod`— escondidas detrás de un
 * `[key: string]: unknown`. Por eso ninguna interfaz de este fichero tiene índice abierto y `contract.spec.ts`
 * las compara contra la forma grabada de la instancia (`npm run contract`).
 */

// --- sobres comunes ---------------------------------------------------------------------------------

export interface Source {
  dataset: string;
  url: string;
}

/**
 * Un mapa que llega en JSON. La clave que se pide **puede no estar**: los recuentos por estado, por método o
 * por medida traen las claves que tienen datos, no las que el lector imagine.
 *
 * Se declara así y no como `Record<string, V>` porque ese tipo promete que cualquier índice es un valor, y esa
 * promesa es la que dejó un `undefined.toLocaleString()` a un paso de la pantalla (ADR-022 §4).
 */
export type ApiMap<V> = Partial<Record<string, V>>;

/** Sobre de un objeto. `territory` es el único sin `source` ni `ingestedAt` en la raíz (ADR-019 §2). */
export interface ApiItem<T> {
  source?: Source;
  ingestedAt?: string | null;
  caveats: string[];
  item: T;
}

/** Sobre de una lista paginada. `catalog` usa `page` como objeto; el resto, campos sueltos. */
export interface ApiPage<T> {
  source?: Source;
  ingestedAt?: string | null;
  caveats: string[];
  total: number;
  page: number;
  size: number;
  items: T[];
  unit?: string;
  snapshotDate?: string;
}

export interface CatalogPage<T> {
  source: Source;
  ingestedAt: string | null;
  caveats: string[];
  page: { number: number; size: number; totalElements: number; totalPages: number; sort: string };
  items: T[];
}

// --- geo --------------------------------------------------------------------------------------------

export interface GeoJsonPolygon {
  type: 'Polygon' | 'MultiPolygon';
  coordinates: number[][][] | number[][][][];
}

export interface DistrictFeature {
  type: 'Feature';
  id: number;
  geometry: GeoJsonPolygon;
  properties: { id: number; name: string; shortName: string; kind: string; padronId: number | null };
}

export interface DistrictBoundaries {
  type: 'FeatureCollection';
  count: number;
  features: DistrictFeature[];
}

// --- territory (el cruce) ---------------------------------------------------------------------------

export interface Coverage {
  total: number;
  withPoint: number;
  assigned: number;
  unassigned: number;
  pointCoverage: number | null;
}

export type MeasureId = 'citizen.requests' | 'urban.premises' | 'urban.licences';

export interface MeasureColumn {
  id: MeasureId;
  module: string;
  unit: string;
  dateField: string;
  dateFieldMeaning: string;
  ingestedAt: string | null;
  coverage: Coverage;
}

export interface DistrictRow {
  districtId: number;
  name: string;
  shortName: string;
  padronId: number | null;
  population: number | null;
  populationYear: number | null;
  values: ApiMap<number>;
  perThousandInhabitants: ApiMap<number>;
}

export type Denominator = 'population' | 'none';

export interface CrossTab {
  axis: string;
  window: { from: string | null; to: string | null };
  denominator: Denominator;
  populationYear: number | null;
  sort: string;
  measures: MeasureColumn[];
  districts: number;
  items: DistrictRow[];
}

export interface DistrictCard {
  window: { from: string | null; to: string | null };
  denominator: Denominator;
  populationYear: number | null;
  measures: MeasureColumn[];
  item: DistrictRow;
  populationSeries: { year: number; population: number }[];
}

/** El estado entero de la pantalla del cruce: lo que se ve es exactamente lo que se pide. */
export interface CrossTabQuery {
  measures: MeasureId[];
  from: string | null;
  to: string | null;
  denominator: Denominator;
  populationYear: number | null;
  /** `district,asc` o `<medida>,desc`. Ordena el backend, siempre (ADR-020 §12). */
  sort: string;
}

export const ALL_MEASURES: readonly MeasureId[] = [
  'citizen.requests',
  'urban.premises',
  'urban.licences',
] as const;

/** Qué cuenta cada unidad, en palabras. Ninguna es intercambiable con otra (ADR-019). */
export const UNIT_LABELS: Record<string, string> = {
  requests: 'quejas',
  premises: 'locales',
  licences: 'licencias',
};

export const MEASURE_LABELS: Record<MeasureId, string> = {
  'citizen.requests': 'Quejas y sugerencias',
  'urban.premises': 'Locales con licencia',
  'urban.licences': 'Licencias de actividad',
};

// --- citizen ----------------------------------------------------------------------------------------

export interface AssignmentBreakdown {
  byAssignment: ApiMap<number>;
  declaredAgrees: number;
  declaredDisagrees: number;
  declaredOnly: number;
  declaredUnmatched: number;
}

export interface CitizenSummary {
  total: number;
  internal: number;
  /** Nulo con la base vacía: el resumen existe antes de que la primera ingesta acabe. */
  earliestRequestedAt: string | null;
  latestRequestedAt: string | null;
  latestUpdatedAt: string | null;
  byStatus: ApiMap<number>;
  assignment: AssignmentBreakdown;
}

export interface ServiceRequest {
  id: number;
  status: string;
  serviceCode: string;
  serviceName: string | null;
  requestedAt: string;
  closedAt: string | null;
  responseHours: number | null;
  lon: number | null;
  lat: number | null;
  assignment: string;
  districtId: number | null;
  districtName: string | null;
  districtDeclared: string | null;
  districtDeclaredId: number | null;
}

export interface CitizenBucket {
  /** `null` en el grupo «sin asignar»: procesos sin etapa, licencias sin año, partidas sin programa. */
  key: string | null;
  label: string | null;
  year: number | null;
  total: number;
  closed: number;
  open: number;
  withPoint: number;
  pointCoverage: number | null;
  internal: number;
  medianResponseHours: number | null;
  population: number | null;
  populationYear: number | null;
  perThousandInhabitants: number | null;
}

/**
 * Cobertura de punto de un año entero. **Solo llega en los ejes de serie** —`district_year` en `citizen`,
 * `licence_year` y `district_licence_year` en `urban`—; en los demás la API manda una lista vacía a propósito,
 * porque no hay dos años que comparar.
 */
export interface YearCoverage {
  year: number;
  total: number;
  withPoint: number;
  assigned: number;
  pointCoverage: number;
}

export interface CitizenAggregation {
  by: string;
  buckets: CitizenBucket[];
  coverageByYear: YearCoverage[];
  assignment: AssignmentBreakdown;
  matched: number;
  unassigned: number;
  internal: number;
}

// --- urban ------------------------------------------------------------------------------------------

export interface UrbanSummary {
  premises: number;
  licences: number;
  earliestCreatedAt: string | null;
  latestUpdatedAt: string | null;
  byStatusCode: ApiMap<number>;
  assignment: ApiMap<number>;
}

export interface Licence {
  year: number;
  fileNumber: number;
  order: number | null;
  typeId: number | null;
  typeName: string | null;
  resolvedOn: string | null;
  resolutionCode: number | null;
  deregisteredAt: string | null;
}

export interface Premises {
  id: number;
  iaeCode: string | null;
  iaeTitle: string | null;
  iaeSection: number | null;
  iaeGroup: number | null;
  statusCode: number | null;
  portalCode: string | null;
  saturatedZone: string | null;
  createdAt: string;
  updatedAt: string | null;
  deregisteredAt: string | null;
  lon: number | null;
  lat: number | null;
  assignment: string;
  districtId: number | null;
  districtName: string | null;
  licences: Licence[];
}

export interface UrbanBucket {
  key: string | null;
  label: string | null;
  year: number | null;
  /**
   * Los registros del grupo **en la unidad que declara la respuesta** (`unit`). Es el campo que evita adivinar:
   * un eje de licencias con cero licencias vale 0 aquí, y `licences || premises` habría enseñado locales.
   */
  total: number;
  premises: number;
  licences: number;
  withPoint: number;
  pointCoverage: number;
  /** Registros con fecha de baja. **No son cierres**: lo dicen los `caveats` de la respuesta. */
  deregistered: number;
  population: number | null;
  populationYear: number | null;
  perThousandInhabitants: number | null;
}

export interface UrbanAggregation {
  by: string;
  unit: string;
  buckets: UrbanBucket[];
  coverageByYear: YearCoverage[];
  assignment: ApiMap<number>;
  matched: number;
  unassigned: number;
}

// --- spending: contratación -------------------------------------------------------------------------

export interface SpendingSummary {
  processes: number;
  byReleaseStatus: ApiMap<number>;
  notInDocumentedList: number;
  withoutStage: number;
  emptyContracts: number;
  awards: number;
  naturalPersonSuppliers: number;
  tenderedAmount: number;
  awardedAmount: number;
  earliestPublishedAt: string | null;
  latestPublishedAt: string | null;
}

export interface Cpv {
  code: string;
  description: string | null;
  main: boolean;
}

export interface ContractingProcess {
  ocid: string;
  fileNumber: number | null;
  inDocumentedList: boolean;
  releaseStatus: string;
  publishedAt: string | null;
  tags: string | null;
  title: string | null;
  description: string | null;
  tenderStatus: string | null;
  procurementMethod: string | null;
  category: string | null;
  awardCriteria: string | null;
  numberOfTenderers: number | null;
  tenderAmount: number | null;
  tenderMinAmount: number | null;
  currency: string | null;
  procuringEntity: string | null;
  stage: string | null;
  awardedAmount: number | null;
  cpv: Cpv[];
  awards: unknown[];
  contracts: unknown[];
}

export interface SpendingBucket {
  key: string | null;
  label: string | null;
  year: number | null;
  total: number;
  processes: number;
  awards: number;
  withRelease: number;
  withoutStage: number;
  tenderedAmount: number;
  awardedAmount: number;
}

export interface SpendingAggregation {
  by: string;
  unit: string;
  overlapping: boolean;
  buckets: SpendingBucket[];
  matched: number;
  total: number;
  withoutRelease: number;
}

// --- spending: presupuesto --------------------------------------------------------------------------

/** Los ocho importes del ciclo presupuestario. Ninguno es «el gasto» a secas (regla 36). */
export interface BudgetAmounts {
  creditInitial: number;
  creditModification: number;
  creditFinal: number;
  committed: number;
  obligations: number;
  payments: number;
  paymentsPending: number;
  creditRemaining: number;
}

export interface BudgetSummary {
  snapshots: number;
  byReadStatus: ApiMap<number>;
  pending: number;
  lines: number;
  /** Nulos mientras el censo esté vacío: son fechas del censo, no constantes del producto. */
  firstSnapshot: string | null;
  lastSnapshot: string | null;
  latestLoaded: string | null;
  latestAmounts: BudgetAmounts;
  redactedHeadings: number;
  linesWithoutProgramme: number;
}

export interface BudgetLine {
  snapshotDate: string;
  concept: string;
  areaId: string | null;
  area: string | null;
  chapterId: number | null;
  chapter: string | null;
  programmeId: string | null;
  programme: string | null;
  organId: string | null;
  organ: string | null;
  itemId: string | null;
  item: string | null;
  heading: string | null;
  headingRedacted: boolean;
  amounts: BudgetAmounts;
}

export interface BudgetBucket {
  key: string | null;
  label: string | null;
  snapshotDate: string | null;
  lines: number;
  amounts: BudgetAmounts;
}

export interface BudgetAggregation {
  by: string;
  unit: string;
  basis: string;
  snapshotDate: string | null;
  items: BudgetBucket[];
  lines: number;
}

// --- spending: subvenciones -------------------------------------------------------------------------

export interface GrantsSummary {
  grants: number;
  granted: number;
  calls: number;
  callBudget: number;
  beneficiaries: number;
  naturalPersonBeneficiaries: number;
  naturalPersonGrants: number;
  byClassification: ApiMap<number>;
  firstYear: number | null;
  lastYear: number | null;
  withoutBeneficiary: number;
  redactedTitles: number;
  impossibleDates: number;
}

export interface Grant {
  id: number;
  callId: number | null;
  call: string | null;
  line: string | null;
  type: string | null;
  title: string | null;
  titleRedacted: boolean;
  fileNumber: string | null;
  requested: number | null;
  granted: number | null;
  annual: number | null;
  annuities: number | null;
  requestedOn: string | null;
  grantedOn: string | null;
  agreedOn: string | null;
  beneficiaryId: string | null;
  beneficiary: string | null;
  /** `null` cuando la concesión no tiene enlace de beneficiario: 2.609 de 2013 y 2014 (ADR-018). */
  naturalPerson: boolean | null;
  classification: string | null;
}

export interface GrantBucket {
  key: string | null;
  label: string | null;
  grants: number;
  granted: number;
  naturalPersonGrants: number;
  beneficiaries: number;
}

export interface GrantAggregation {
  by: string;
  unit: string;
  buckets: GrantBucket[];
}

// --- catalog ----------------------------------------------------------------------------------------

export interface CatalogSummary {
  datasets: number;
  byDeclaredFreshness: ApiMap<number>;
  byPeriodicity: ApiMap<number>;
  withApi: number;
  open: number;
  explorable: number;
  withGeo: number;
  latestSnapshotOn: string | null;
  withoutSnapshot: number;
  byObservationMethod: ApiMap<number>;
  withoutObservation: number;
  notListed: number;
  apiInventory: ApiMap<number | string>;
  federation: ApiMap<number | string>;
  thresholds: ApiMap<number>;
}

/**
 * Una ficha del catálogo municipal, con los dos ejes que no se mezclan (ADR-005).
 *
 * Los nombres son los de la respuesta y no los que parecen: lo **observado** llega con prefijo `latest`
 * (`latestObservationMethod`, `latestObservedChange`, `latestFreshness`) mientras el parámetro de ordenación se
 * llama `observedLastChange`. Escribir el del `sort` en el cuerpo dejó tres columnas vacías en la pantalla
 * publicada sin que nada fallara (ADR-022 §4).
 */
export interface Dataset {
  id: number;
  title: string;
  issued: string | null;
  declaredModified: string | null;
  metadataUpdated: string | null;
  declaredPeriodicity: string | null;
  periodicityDays: number | null;
  publicationStatus: string | null;
  hasGeo: boolean | null;
  open: boolean | null;
  explorable: boolean;
  hasApi: boolean;
  apiTag: string | null;
  federated: boolean;
  federatedUrl: string | null;
  /** Categoría del eje **declarado**: `ON_TIME` … `NOT_EVALUABLE`. */
  latestFreshness: string | null;
  latestRatio: number | null;
  latestSnapshotOn: string | null;
  /** Cuándo preguntamos nosotros al origen. */
  observedAt: string | null;
  latestObservationMethod: string | null;
  /** Lo que el origen dice que cambió, medido por nosotros. No hay categoría observada (ADR-005 §6). */
  latestObservedChange: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  /** `false` cuando la ficha dejó de aparecer en el listado municipal. No se borra nunca (ADR-013). */
  listed: boolean;
  delistedAt: string | null;
}
