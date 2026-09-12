/**
 * Los cuerpos que devuelve la API, escritos contra las respuestas reales de la instancia y no de memoria
 * (regla 1). El contrato vive en `/v3/api-docs`; esto es su lectura en TypeScript.
 */

// --- sobres comunes ---------------------------------------------------------------------------------

export interface Source {
  dataset: string;
  url: string;
}

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
  values: Record<string, number>;
  perThousandInhabitants: Record<string, number>;
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
  byAssignment: Record<string, number>;
  declaredAgrees: number;
  declaredDisagrees: number;
  declaredOnly: number;
  declaredUnmatched: number;
}

export interface CitizenSummary {
  total: number;
  internal: number;
  earliestRequestedAt: string;
  latestRequestedAt: string;
  latestUpdatedAt: string;
  byStatus: Record<string, number>;
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
  key: string;
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

export interface CitizenAggregation {
  by: string;
  buckets: CitizenBucket[];
  coverageByYear: { year: number; total: number; withPoint: number; pointCoverage: number | null }[];
  assignment: AssignmentBreakdown;
  matched: number;
  unassigned: number;
  internal: number;
}

// --- urban ------------------------------------------------------------------------------------------

export interface UrbanSummary {
  premises: number;
  licences: number;
  earliestCreatedAt: string;
  latestUpdatedAt: string;
  byStatusCode: Record<string, number>;
  assignment: Record<string, number>;
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
  key: string;
  label: string | null;
  year: number | null;
  premises: number;
  licences: number;
  withPoint: number | null;
  pointCoverage: number | null;
  population: number | null;
  populationYear: number | null;
  perThousandInhabitants: number | null;
}

export interface UrbanAggregation {
  by: string;
  unit: string;
  buckets: UrbanBucket[];
  coverageByYear?: { year: number; total: number; withPoint: number; pointCoverage: number | null }[];
}

// --- spending: contratación -------------------------------------------------------------------------

export interface SpendingSummary {
  processes: number;
  byReleaseStatus: Record<string, number>;
  notInDocumentedList: number;
  withoutStage: number;
  emptyContracts: number;
  awards: number;
  naturalPersonSuppliers: number;
  tenderedAmount: number;
  awardedAmount: number;
  earliestPublishedAt: string;
  latestPublishedAt: string;
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
  key: string;
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
  byReadStatus: Record<string, number>;
  pending: number;
  lines: number;
  firstSnapshot: string;
  lastSnapshot: string;
  latestLoaded: string;
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
  key: string;
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
  byClassification: Record<string, number>;
  firstYear: number;
  lastYear: number;
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
  naturalPerson: boolean;
  classification: string | null;
}

export interface GrantBucket {
  key: string;
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
  byDeclaredFreshness: Record<string, number>;
  byPeriodicity: Record<string, number>;
  withApi: number;
  open: number;
  explorable: number;
  withGeo: number;
  latestSnapshotOn: string;
  withoutSnapshot: number;
  byObservationMethod: Record<string, number>;
  withoutObservation: number;
  notListed: number;
  apiInventory: Record<string, number | string>;
  federation: Record<string, number | string>;
  thresholds: Record<string, number>;
}

export interface Dataset {
  id: number;
  title: string;
  issued: string | null;
  declaredModified: string | null;
  metadataUpdated: string | null;
  declaredPeriodicity: string | null;
  periodicityDays: number | null;
  publicationStatus: string | null;
  [key: string]: unknown;
}
