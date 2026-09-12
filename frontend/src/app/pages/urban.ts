import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory, Query } from '../core/api';
import { byKey, date, integer, keyOf, labelOf, percent } from '../core/format';
import { Premises, Source, UrbanAggregation, UrbanSummary } from '../core/types';
import { BarChart, Bar } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, FilterValues, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { Stat, Stats } from '../ui/stats';

type Axis = 'activity' | 'licence_year' | 'district' | 'licence_type' | 'status';

/**
 * Actividad urbana privada: locales con licencia y sus licencias. Es la fuente territorial con **mejor
 * cobertura** del producto, en torno al 90 %, y la que obliga a decir a cada paso **qué unidad se está
 * contando**: un local con doce licencias es un local y son doce licencias (ADR-016).
 */
@Component({
  selector: 'obs-urban',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon],
  templateUrl: './urban.html',
})
export class UrbanPage {
  private readonly api = inject(Observatory);

  readonly summary = signal<UrbanSummary | null>(null);
  readonly aggregation = signal<UrbanAggregation | null>(null);
  readonly premises = signal<Premises[]>([]);
  readonly total = signal(0);
  readonly caveats = signal<string[]>([]);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly axis = signal<Axis>('activity');
  readonly page = signal(0);
  readonly sort = signal('createdAt,desc');
  readonly filters = signal<FilterValues>({ iaeSection: '', statusCode: '', assignment: '', licenceYear: '' });

  protected readonly integer = integer;
  protected readonly size = 25;
  protected readonly axes: { key: Axis; label: string }[] = [
    { key: 'activity', label: 'Actividad' },
    { key: 'licence_year', label: 'Año de licencia' },
    { key: 'district', label: 'Junta' },
    { key: 'licence_type', label: 'Tipo de licencia' },
    { key: 'status', label: 'Estado' },
  ];

  readonly filterDefs: FilterDef[] = [
    {
      key: 'iaeSection',
      label: 'Sección IAE',
      kind: 'select',
      options: [
        { value: '1', label: '1 — Empresariales' },
        { value: '2', label: '2 — Profesionales' },
        { value: '3', label: '3 — Artísticas' },
      ],
    },
    {
      key: 'statusCode',
      label: 'Estado (código)',
      kind: 'select',
      options: ['0', '1', '2', '3'].map((value) => ({ value, label: value })),
      hint: 'Sin taxonomía publicada: se da el código',
    },
    {
      key: 'assignment',
      label: 'Situación territorial',
      kind: 'select',
      options: [
        { value: 'RESOLVED', label: 'Situado en una junta' },
        { value: 'NO_POINT', label: 'Sin coordenadas' },
        { value: 'OUTSIDE', label: 'Fuera del término' },
      ],
    },
    { key: 'licenceYear', label: 'Año de licencia', kind: 'number', placeholder: '2024' },
  ];

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary();
    if (!summary) {
      return [];
    }
    const resolved = summary.assignment['RESOLVED'] ?? 0;
    return [
      {
        value: integer(summary.premises),
        label: 'Locales con licencia',
        note: `Desde ${date(summary.earliestCreatedAt)}. Es un registro de licencias concedidas: ninguna cifra se llama «locales abiertos».`,
      },
      {
        value: integer(summary.licences),
        label: 'Licencias',
        note: 'Otra unidad distinta: un local puede acumular varias a lo largo del tiempo.',
      },
      {
        value: percent(resolved / summary.premises),
        label: 'Se pueden situar',
        note: `${integer(resolved)} locales caen dentro de una junta. Es la mejor cobertura territorial del observatorio.`,
      },
      {
        value: integer(summary.byStatusCode['1'] ?? 0),
        label: 'En estado 1',
        note: 'Qué significa ese código no lo publica nadie. Se da el número y no se le pone nombre.',
        caution: true,
      },
    ];
  });

  readonly unit = computed(() => this.aggregation()?.unit ?? 'premises');

  readonly bars = computed<Bar[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by !== 'licence_year') {
      return [];
    }
    return [...aggregation.buckets]
      .filter((bucket) => Number(bucket.key) >= 1990)
      .sort(byKey)
      .map((bucket) => ({ key: keyOf(bucket), label: keyOf(bucket), value: bucket.licences || bucket.premises }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by === 'licence_year') {
      return [];
    }
    const licences = aggregation.unit === 'licences';
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: labelOf(bucket),
        value: licences ? bucket.licences : bucket.premises,
        note: licences
          ? `${integer(bucket.premises)} locales`
          : `${integer(bucket.licences)} licencias${bucket.perThousandInhabitants ? ` · ${bucket.perThousandInhabitants.toFixed(1)} por mil hab.` : ''}`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 20);
  });

  readonly columns: Column<Premises>[] = [
    {
      key: 'iaeTitle',
      label: 'Actividad (epígrafe IAE)',
      wide: true,
      get: (row) => row.iaeTitle ?? `código ${row.iaeCode}`,
      sub: (row) => (row.districtName ? `junta resuelta: ${row.districtName}` : 'sin coordenadas'),
    },
    { key: 'createdAt', label: 'Alta del local', sortable: 'createdAt', get: (row) => date(row.createdAt) },
    {
      key: 'licences',
      label: 'Licencias',
      numeric: true,
      get: (row) => String(row.licences.length),
      sub: (row) => {
        const years = row.licences.map((licence) => licence.year).filter(Boolean);
        return years.length > 0 ? `${Math.min(...years)}–${Math.max(...years)}` : null;
      },
    },
    { key: 'statusCode', label: 'Estado', numeric: true, get: (row) => String(row.statusCode ?? '—') },
    { key: 'zone', label: 'Zona saturada', get: (row) => row.saturatedZone ?? '—' },
  ];

  constructor() {
    this.api.urbanSummary().subscribe({
      next: (response) => {
        this.summary.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer el resumen de actividad urbana.'),
    });
    this.loadAggregation();
    this.loadPremises();
  }

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.loadAggregation();
  }

  setFilter(change: { key: string; value: string }): void {
    this.filters.update((current) => ({ ...current, [change.key]: change.value }));
    this.page.set(0);
    this.loadPremises();
  }

  clearFilters(): void {
    this.filters.set({ iaeSection: '', statusCode: '', assignment: '', licenceYear: '' });
    this.page.set(0);
    this.loadPremises();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.loadPremises();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadPremises();
  }

  private loadAggregation(): void {
    this.api.urbanAggregation(this.axis()).subscribe({
      next: (response) => this.aggregation.set(response.item),
      error: () => this.error.set('No se ha podido leer la agregación de actividad urbana.'),
    });
  }

  private loadPremises(): void {
    const query: Query = { page: this.page(), size: this.size, sort: this.sort(), ...this.filters() };
    this.api.urbanPremises(query).subscribe({
      next: (response) => {
        this.premises.set(response.items);
        this.total.set(response.total);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'No se han podido leer los locales.'),
    });
  }
}
