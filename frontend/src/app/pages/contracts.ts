import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory, Query } from '../core/api';
import { byKey, date, euro, euroShort, integer, keyOf, labelOf } from '../core/format';
import { ContractingProcess, Source, SpendingAggregation, SpendingSummary } from '../core/types';
import { BarChart, Bar } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, FilterValues, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { Stat, Stats } from '../ui/stats';

type Axis = 'year' | 'supplier' | 'procuring_entity' | 'category' | 'cpv' | 'stage' | 'release_status';

const AXES: { key: Axis; label: string }[] = [
  { key: 'year', label: 'Año' },
  { key: 'supplier', label: 'Adjudicataria' },
  { key: 'procuring_entity', label: 'Órgano' },
  { key: 'category', label: 'Tipo' },
  { key: 'cpv', label: 'CPV' },
  { key: 'stage', label: 'Etapa' },
  { key: 'release_status', label: 'Publicación' },
];

/**
 * Las dos cifras de esta fuente, que **no son intercambiables** (ADR-017, regla 33): el ranking ordena por una
 * o por la otra y dice por cuál.
 *
 * Antes pintaba `awardedAmount || tenderedAmount`, que mezclaba las dos en la misma barra —un grupo sin
 * adjudicar caía a lo licitado sin avisar— y además dejaba la barra sin nombre. Es exactamente lo que la ADR de
 * la fuente prohíbe hacer con estos dos importes.
 */
const FIGURES = [
  { key: 'awardedAmount', label: 'Adjudicado' },
  { key: 'tenderedAmount', label: 'Licitado' },
] as const;

type FigureKey = (typeof FIGURES)[number]['key'];

/**
 * Las **dos** etapas que existen, comprobadas contra la API y no supuestas: `stage` solo se publica donde el
 * documento lo sostiene, así que 4.292 de los 8.005 procesos salen **sin etapa** y eso no es un hueco que
 * rellenar (ADR-017). No hay «licitado» ni «adjudicado» como etapa: el filtro los rechazaría.
 */
const STAGE_LABELS: Record<string, string> = {
  PLANNED: 'Planificado',
  COMMITTED: 'Contrato firmado',
};

/**
 * La contratación pública (OCDS). Dos avisos que la pantalla no puede suavizar, porque son el hallazgo de S3.1:
 * el listado documentado **esconde 2.271 procesos** y ninguna cifra de aquí es dinero pagado.
 */
@Component({
  selector: 'obs-contracts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon],
  templateUrl: './contracts.html',
})
export class ContractsPage {
  private readonly api = inject(Observatory);

  readonly summary = signal<SpendingSummary | null>(null);
  readonly aggregation = signal<SpendingAggregation | null>(null);
  readonly processes = signal<ContractingProcess[]>([]);
  readonly total = signal(0);
  readonly caveats = signal<string[]>([]);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly axis = signal<Axis>('year');
  readonly figure = signal<FigureKey>('awardedAmount');
  readonly page = signal(0);
  readonly sort = signal('publishedAt,desc');
  readonly filters = signal<FilterValues>({
    q: '',
    year: '',
    category: '',
    stage: '',
    inDocumentedList: '',
    procuringEntity: '',
  });

  protected readonly axes = AXES;
  protected readonly figures = FIGURES;
  protected readonly euroShort = euroShort;
  protected readonly size = 25;

  readonly filterDefs: FilterDef[] = [
    { key: 'q', label: 'Buscar en el título', kind: 'text', placeholder: 'obras, suministro, limpieza…' },
    { key: 'year', label: 'Año', kind: 'number', placeholder: '2025' },
    {
      key: 'category',
      label: 'Tipo',
      kind: 'select',
      options: [
        { value: 'goods', label: 'Suministros' },
        { value: 'services', label: 'Servicios' },
        { value: 'works', label: 'Obras' },
      ],
    },
    {
      key: 'stage',
      label: 'Etapa',
      kind: 'select',
      options: Object.entries(STAGE_LABELS).map(([value, label]) => ({ value, label })),
    },
    {
      key: 'inDocumentedList',
      label: 'En el listado documentado',
      kind: 'select',
      options: [
        { value: 'true', label: 'Sí aparece' },
        { value: 'false', label: 'No: la API lo esconde' },
      ],
      hint: '2.271 procesos solo salen con el interruptor puesto',
    },
  ];

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary();
    if (!summary) {
      return [];
    }
    const absent = summary.byReleaseStatus['ABSENT'] ?? 0;
    return [
      {
        value: integer(summary.processes),
        label: 'Procesos',
        note: `De ${date(summary.earliestPublishedAt)} a ${date(summary.latestPublishedAt)}. Se llaman procesos y no contratos porque muchos no llegan a contrato.`,
      },
      {
        value: euroShort(summary.awardedAmount),
        label: 'Importe adjudicado',
        note: 'Lo adjudicado, que no es lo pagado. Lo pagado está en el presupuesto.',
      },
      {
        value: euroShort(summary.tenderedAmount),
        label: 'Importe licitado',
        note: 'Lo que se sacó a concurso. Va en columna aparte del adjudicado a propósito.',
      },
      {
        value: integer(summary.notInDocumentedList),
        label: 'Escondidos del listado',
        note: `El listado que la API documenta publica ${integer(summary.processes - summary.notInDocumentedList)} de ${integer(summary.processes)}. El resto solo sale mandando un parámetro que no filtra por fecha.`,
        caution: true,
      },
      {
        value: integer(summary.emptyContracts),
        label: 'Contratos vacíos',
        note: 'Con identificador y sin fecha de firma: no se puede saber si se firmaron. Salen sin etapa.',
        caution: true,
      },
      {
        value: integer(absent),
        label: 'Sin detalle publicado',
        note: 'El ayuntamiento aún no publica su documento. Es un estado legítimo, no un error nuestro.',
      },
    ];
  });

  readonly bars = computed<Bar[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by !== 'year') {
      return [];
    }
    return [...aggregation.buckets]
      .sort(byKey)
      .map((bucket) => ({
        key: keyOf(bucket),
        label: keyOf(bucket),
        value: bucket.tenderedAmount,
        value2: bucket.awardedAmount,
      }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by === 'year') {
      return [];
    }
    const figure = this.figure();
    const other = figure === 'awardedAmount' ? 'tenderedAmount' : 'awardedAmount';
    const otherLabel = figure === 'awardedAmount' ? 'licitado' : 'adjudicado';
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: this.stageAware(bucket),
        value: bucket[figure],
        note: `${integer(bucket.processes)} procesos · ${euroShort(bucket[other])} ${otherLabel}`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 18);
  });

  readonly figureLabel = computed(
    () => FIGURES.find((option) => option.key === this.figure())?.label ?? '',
  );

  readonly columns: Column<ContractingProcess>[] = [
    {
      key: 'title',
      label: 'Objeto del contrato',
      wide: true,
      get: (row) => row.title ?? '(sin título publicado)',
      sub: (row) =>
        [row.procuringEntity, row.stage ? STAGE_LABELS[row.stage] : 'sin etapa'].filter(Boolean).join(' · '),
    },
    {
      key: 'publishedAt',
      label: 'Publicado',
      sortable: 'publishedAt',
      get: (row) => date(row.publishedAt),
    },
    {
      key: 'tenderAmount',
      label: 'Licitado',
      numeric: true,
      sortable: 'tenderAmount',
      get: (row) => euro(row.tenderAmount),
    },
    {
      key: 'awardedAmount',
      label: 'Adjudicado',
      numeric: true,
      sortable: 'awardedAmount',
      get: (row) => euro(row.awardedAmount),
    },
    {
      key: 'inDocumentedList',
      label: 'Listado',
      get: (row) => (row.inDocumentedList ? 'Sí' : 'Escondido'),
    },
  ];

  constructor() {
    this.api.spendingSummary().subscribe({
      next: (response) => {
        this.summary.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer el resumen de contratación.'),
    });
    this.loadAggregation();
    this.loadProcesses();
  }

  readonly overlapping = computed(() => this.aggregation()?.overlapping ?? false);

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.loadAggregation();
  }

  setFigure(figure: FigureKey): void {
    this.figure.set(figure);
  }

  setFilter(change: { key: string; value: string }): void {
    this.filters.update((current) => ({ ...current, [change.key]: change.value }));
    this.page.set(0);
    this.loadProcesses();
  }

  clearFilters(): void {
    this.filters.set({
      q: '',
      year: '',
      category: '',
      stage: '',
      inDocumentedList: '',
      procuringEntity: '',
    });
    this.page.set(0);
    this.loadProcesses();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.loadProcesses();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadProcesses();
  }

  /** En el eje de etapa el nulo tiene nombre propio: es el hecho de que el documento no la sostiene. */
  private stageAware(bucket: { key: string | null; label: string | null }): string {
    if (this.axis() === 'stage') {
      return bucket.key ? (STAGE_LABELS[bucket.key] ?? bucket.key) : 'Sin etapa (el documento no la sostiene)';
    }
    return labelOf(bucket);
  }

  private loadAggregation(): void {
    this.api.spendingAggregation(this.axis()).subscribe({
      next: (response) => this.aggregation.set(response.item),
      error: () => this.error.set('No se ha podido leer la agregación de contratación.'),
    });
  }

  private loadProcesses(): void {
    const query: Query = { page: this.page(), size: this.size, sort: this.sort(), ...this.filters() };
    this.api.processes(query).subscribe({
      next: (response) => {
        this.processes.set(response.items);
        this.total.set(response.total);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'No se han podido leer los procesos.'),
    });
  }
}
