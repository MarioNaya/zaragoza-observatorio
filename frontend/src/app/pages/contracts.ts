import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory } from '../core/api';
import { byKey, date, euro, euroShort, integer, keyOf, labelOf } from '../core/format';
import { Explorer, FilterValues, Loaded } from '../core/state';
import { ApiPage, ContractingProcess } from '../core/types';
import { Bar, BarChart } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { State } from '../ui/state';
import { Stat, Stats } from '../ui/stats';

type Axis = 'year' | 'supplier' | 'procuring_entity' | 'category' | 'cpv' | 'stage' | 'release_status';

const FILTERS: FilterValues = {
  q: '',
  year: '',
  category: '',
  stage: '',
  inDocumentedList: '',
  procuringEntity: '',
};

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
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon, State],
  templateUrl: './contracts.html',
})
export class ContractsPage {
  private readonly api = inject(Observatory);

  readonly summary = new Loaded(() => this.api.spendingSummary(), 'el resumen de contratación');

  readonly axis = signal<Axis>('year');
  readonly figure = signal<FigureKey>('awardedAmount');

  readonly aggregation = new Loaded(
    () => this.api.spendingAggregation(this.axis()),
    'el reparto de la contratación',
  );

  readonly explorer = new Explorer<ContractingProcess, ApiPage<ContractingProcess>>({
    request: (query) => this.api.processes(query),
    rows: (response) => response.items,
    total: (response) => response.total,
    sort: 'publishedAt,desc',
    filters: FILTERS,
    what: 'los procesos',
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
    const summary = this.summary.value();
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
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by !== 'year') {
      return [];
    }
    return [...aggregation.buckets]
      .sort(byKey)
      .map((bucket) => ({
        key: keyOf(bucket),
        label: keyOf(bucket),
        // Un año sin nada que sumar trae nulo, y en una barra el nulo se dibuja a cero.
        value: bucket.tenderedAmount ?? 0,
        value2: bucket.awardedAmount ?? 0,
      }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation.value();
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
        value: bucket[figure] ?? 0,
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

  readonly overlapping = computed(() => this.aggregation.value()?.overlapping ?? false);

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.aggregation.reload();
  }

  setFigure(figure: FigureKey): void {
    this.figure.set(figure);
  }

  /** En el eje de etapa el nulo tiene nombre propio: es el hecho de que el documento no la sostiene. */
  private stageAware(bucket: { key: string | null; label: string | null }): string {
    if (this.axis() === 'stage') {
      return bucket.key ? (STAGE_LABELS[bucket.key] ?? bucket.key) : 'Sin etapa (el documento no la sostiene)';
    }
    return labelOf(bucket);
  }
}
