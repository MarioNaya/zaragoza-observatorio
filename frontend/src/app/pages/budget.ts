import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory, Query } from '../core/api';
import { byKey, euro, euroCents, euroShort, integer, keyOf, labelOf } from '../core/format';
import { BudgetAggregation, BudgetLine, BudgetSummary, Source } from '../core/types';

import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, FilterValues, Filters } from '../ui/filters';
import { LineChart, Series } from '../ui/line-chart';
import { Ranking, RankRow } from '../ui/ranking';
import { Stat, Stats } from '../ui/stats';

type Axis = 'year' | 'chapter' | 'area' | 'programme' | 'organ';

const AXES: { key: Axis; label: string }[] = [
  { key: 'chapter', label: 'Capítulo' },
  { key: 'area', label: 'Área de gasto' },
  { key: 'programme', label: 'Programa' },
  { key: 'organ', label: 'Órgano' },
];

/** Las cuatro cifras que importan del ciclo, en su orden contable. Ninguna se llama «gasto» a secas. */
const FIGURES = [
  { key: 'creditFinal', label: 'Crédito definitivo', note: 'lo presupuestado, ya con modificaciones' },
  { key: 'committed', label: 'Comprometido', note: 'lo comprometido con un tercero' },
  { key: 'obligations', label: 'Obligación neta', note: 'el gasto ejecutado' },
  { key: 'payments', label: 'Pago neto', note: 'lo efectivamente pagado' },
] as const;

type FigureKey = (typeof FIGURES)[number]['key'];

/**
 * El presupuesto de gastos: la única fuente del producto con **dinero pagado** (regla 36).
 *
 * Dos cosas que esta pantalla no puede dejar de decir, porque sin ellas las cifras se leen mal: que las cuatro
 * columnas **no son intercambiables** —y por eso la serie las enseña a la vez en vez de elegir una—, y que cada
 * grupo sale de **una instantánea concreta**, porque sumar las doce fotos de un año contaría el mismo euro doce
 * veces. La fecha de la foto viaja en la respuesta y se imprime.
 */
@Component({
  selector: 'obs-budget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, LineChart, Ranking, DataTable, Filters, Colophon],
  templateUrl: './budget.html',
})
export class BudgetPage {
  private readonly api = inject(Observatory);

  readonly summary = signal<BudgetSummary | null>(null);
  readonly aggregation = signal<BudgetAggregation | null>(null);
  /**
   * La serie por ejercicio va **aparte** del eje que elige quien lee: el gráfico de arriba enseña siempre los
   * veinte ejercicios y el selector de abajo cambia el reparto dentro de una instantánea. Son dos preguntas
   * distintas y antes compartían una sola petición, así que elegir «Capítulo» dejaba el gráfico vacío.
   */
  readonly years = signal<BudgetAggregation | null>(null);
  readonly lines = signal<BudgetLine[]>([]);
  readonly lineTotal = signal(0);
  readonly snapshotDate = signal<string | null>(null);
  readonly caveats = signal<string[]>([]);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly loading = signal(true);

  readonly axis = signal<Axis>('chapter');
  readonly figure = signal<FigureKey>('obligations');
  readonly page = signal(0);
  readonly sort = signal('obligations,desc');
  readonly filters = signal<FilterValues>({ chapter: '', area: '', programme: '', organ: '', q: '' });

  protected readonly axes = AXES;
  protected readonly figures = FIGURES;
  protected readonly euroShort = euroShort;
  protected readonly size = 25;

  readonly filterDefs = computed<FilterDef[]>(() => [
    { key: 'q', label: 'Buscar en la partida', kind: 'text', placeholder: 'alumbrado, biblioteca, IBI…' },
    { key: 'chapter', label: 'Capítulo', kind: 'select', options: this.chapterOptions() },
    { key: 'programme', label: 'Programa', kind: 'text', placeholder: 'código, p. ej. 4411' },
    { key: 'organ', label: 'Órgano', kind: 'text', placeholder: 'código, p. ej. MOV' },
  ]);

  private readonly chapterOptions = signal<{ value: string; label: string }[]>([]);

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary();
    if (!summary) {
      return [];
    }
    const a = summary.latestAmounts;
    return [
      {
        value: euroShort(a.obligations),
        label: 'Gasto ejecutado',
        note: `Obligación neta de la foto de ${summary.latestLoaded}, acumulada desde enero. Es la única cifra del observatorio que mide gasto reconocido.`,
      },
      {
        value: euroShort(a.payments),
        label: 'Pagado',
        note: `Quedan ${euroShort(a.paymentsPending)} pendientes de pago sobre lo ya reconocido.`,
      },
      {
        value: euroShort(a.creditFinal),
        label: 'Crédito definitivo',
        note: 'Lo presupuestado con sus modificaciones. No es gasto: es el techo.',
      },
      {
        value: integer(summary.lines),
        label: 'Partidas',
        note: `En ${integer(summary.snapshots)} instantáneas datadas, de ${summary.firstSnapshot} a ${summary.lastSnapshot}.`,
      },
      {
        value: integer(summary.linesWithoutProgramme),
        label: 'Sin programa',
        note: 'El programa no existe en 2010-2014 y falta en otros seis ejercicios. El hueco se ve, no se rellena.',
        caution: true,
      },
    ];
  });

  /** Las cuatro etapas a lo largo de los ejercicios. Una sola escala: son todas euros. */
  readonly series = computed<Series[]>(() => {
    const aggregation = this.years();
    if (!aggregation) {
      return [];
    }
    const ordered = [...aggregation.items].sort(byKey);
    return FIGURES.map((figure) => ({
      name: figure.label,
      points: ordered.map((bucket) => ({
        key: keyOf(bucket),
        label: keyOf(bucket),
        value: bucket.amounts[figure.key],
      })),
    }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation) {
      return [];
    }
    return [...aggregation.items]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: labelOf(bucket),
        value: bucket.amounts[this.figure()],
        note: `${integer(bucket.lines)} partidas`,
      }))
      .filter((row) => row.value !== 0)
      .sort((a, b) => b.value - a.value)
      .slice(0, 18);
  });

  readonly columns = computed<Column<BudgetLine>[]>(() => [
    {
      key: 'heading',
      label: 'Partida',
      wide: true,
      get: (line) => (line.headingRedacted ? '(nombre omitido: nombra a una persona)' : (line.heading ?? '—')),
      sub: (line) => [line.programme, line.organ].filter(Boolean).join(' · ') || null,
    },
    { key: 'concept', label: 'Concepto', get: (line) => line.concept, sortable: 'concept' },
    { key: 'chapter', label: 'Capítulo', get: (line) => line.chapter ?? '—' },
    {
      key: 'creditFinal',
      label: 'Crédito def.',
      numeric: true,
      sortable: 'creditFinal',
      get: (line) => euro(line.amounts.creditFinal),
    },
    {
      key: 'obligations',
      label: 'Ejecutado',
      numeric: true,
      sortable: 'obligations',
      get: (line) => euro(line.amounts.obligations),
    },
    {
      key: 'payments',
      label: 'Pagado',
      numeric: true,
      sortable: 'payments',
      get: (line) => euroCents(line.amounts.payments),
    },
  ]);

  readonly figureLabel = computed(() => FIGURES.find((f) => f.key === this.figure())?.label ?? '');

  constructor() {
    this.api.budgetSummary().subscribe({
      next: (response) => {
        this.summary.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer el resumen del presupuesto.'),
    });
    this.api.budgetAggregation('chapter').subscribe({
      next: (response) =>
        this.chapterOptions.set(
          response.item.items
            // El capítulo sin clave no es una opción de filtro: no se puede filtrar por «ninguno».
            .filter((bucket): bucket is typeof bucket & { key: string } => !!bucket.key)
            .map((bucket) => ({ value: bucket.key, label: `${bucket.key} — ${bucket.label ?? ''}` })),
        ),
      error: () => undefined,
    });
    this.api.budgetAggregation('year').subscribe({
      next: (response) => this.years.set(response.item),
      error: () => this.error.set('No se ha podido leer la serie por ejercicio.'),
    });
    this.loadAggregation();
    this.loadLines();
  }

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
    this.loadLines();
  }

  clearFilters(): void {
    this.filters.set({ chapter: '', area: '', programme: '', organ: '', q: '' });
    this.page.set(0);
    this.loadLines();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.loadLines();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadLines();
  }

  private loadAggregation(): void {
    this.api.budgetAggregation(this.axis()).subscribe({
      next: (response) => this.aggregation.set(response.item),
      error: () => this.error.set('No se ha podido leer la agregación del presupuesto.'),
    });
  }

  private loadLines(): void {
    this.loading.set(true);
    const query: Query = { page: this.page(), size: this.size, sort: this.sort(), ...this.filters() };
    this.api.budgetLines(query).subscribe({
      next: (response) => {
        this.lines.set(response.items);
        this.lineTotal.set(response.total);
        this.snapshotDate.set(response.snapshotDate ?? null);
        this.loading.set(false);
      },
      error: (failure) => {
        this.loading.set(false);
        this.error.set(failure?.error?.detail ?? 'No se han podido leer las partidas.');
      },
    });
  }
}
