import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory, Query } from '../core/api';
import { date, dateTime, hours, integer, month, percent } from '../core/format';
import { CitizenAggregation, CitizenSummary, ServiceRequest, Source } from '../core/types';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, FilterValues, Filters } from '../ui/filters';
import { LineChart, Series } from '../ui/line-chart';
import { Ranking, RankRow } from '../ui/ranking';
import { Stat, Stats } from '../ui/stats';

type Axis = 'month' | 'category' | 'district';

/**
 * Quejas y sugerencias. Dos hechos gobiernan la lectura y van escritos en la propia pantalla: **el texto de la
 * queja no está aquí** porque no se pide al origen (ADR-012), y **solo tres de cada diez se pueden situar** en
 * una junta, así que todo lo territorial de esta fuente se lee con su cobertura al lado.
 */
@Component({
  selector: 'obs-citizen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, LineChart, Ranking, DataTable, Filters, Colophon],
  templateUrl: './citizen.html',
})
export class CitizenPage {
  private readonly api = inject(Observatory);

  readonly summary = signal<CitizenSummary | null>(null);
  readonly aggregation = signal<CitizenAggregation | null>(null);
  readonly requests = signal<ServiceRequest[]>([]);
  readonly total = signal(0);
  readonly caveats = signal<string[]>([]);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly axis = signal<Axis>('month');
  readonly page = signal(0);
  readonly sort = signal('requestedAt,desc');
  readonly filters = signal<FilterValues>({ status: '', assignment: '', internal: '', from: '', to: '' });

  protected readonly integer = integer;
  protected readonly size = 25;
  protected readonly axes: { key: Axis; label: string }[] = [
    { key: 'month', label: 'Por mes' },
    { key: 'category', label: 'Por categoría' },
    { key: 'district', label: 'Por junta' },
  ];

  readonly filterDefs: FilterDef[] = [
    {
      key: 'status',
      label: 'Estado',
      kind: 'select',
      options: [
        { value: 'OPEN', label: 'Abiertas' },
        { value: 'CLOSED', label: 'Cerradas' },
      ],
    },
    {
      key: 'assignment',
      label: 'Situación territorial',
      kind: 'select',
      options: [
        { value: 'RESOLVED', label: 'Situada en una junta' },
        { value: 'NO_POINT', label: 'Sin coordenadas' },
        { value: 'OUTSIDE', label: 'Fuera del término' },
      ],
      hint: 'Sin punto no hay junta: no se geocodifica',
    },
    {
      key: 'internal',
      label: 'Servicios internos',
      kind: 'select',
      options: [
        { value: 'exclude', label: 'Quitarlos' },
        { value: 'only', label: 'Solo ellos' },
      ],
      hint: 'Por defecto cuentan',
    },
    { key: 'from', label: 'Desde', kind: 'date' },
    { key: 'to', label: 'Hasta', kind: 'date' },
  ];

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary();
    if (!summary) {
      return [];
    }
    const resolved = summary.assignment.byAssignment['RESOLVED'] ?? 0;
    return [
      {
        value: integer(summary.total),
        label: 'Quejas y sugerencias',
        note: `De ${date(summary.earliestRequestedAt)} a ${date(summary.latestRequestedAt)}. El listado abierto no son todas: las estadísticas municipales cuentan del orden de 40.000 cerradas al año.`,
      },
      {
        value: integer(summary.byStatus['CLOSED'] ?? 0),
        label: 'Cerradas',
        note: `Quedan ${integer(summary.byStatus['OPEN'] ?? 0)} abiertas.`,
      },
      {
        value: percent(resolved / summary.total),
        label: 'Se pueden situar',
        note: `${integer(resolved)} traen coordenadas y caen dentro de una junta. El resto no está repartido por ninguna parte: no se geocodifica por dirección.`,
        caution: true,
      },
      {
        value: integer(summary.internal),
        label: 'Servicios internos',
        note: 'No son quejas ciudadanas y siguen contando por defecto. El filtro los quita si quieres.',
      },
    ];
  });

  readonly series = computed<Series[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by !== 'month') {
      return [];
    }
    const ordered = [...aggregation.buckets].sort((a, b) => a.key.localeCompare(b.key));
    return [
      {
        name: 'Quejas presentadas',
        points: ordered.map((bucket) => ({
          key: bucket.key,
          label: month(bucket.key),
          value: bucket.total,
        })),
      },
    ];
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by === 'month') {
      return [];
    }
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: bucket.key,
        label: bucket.label ?? bucket.key,
        value: bucket.total,
        note:
          aggregation.by === 'district'
            ? `${integer(bucket.closed)} cerradas · mediana de respuesta ${hours(bucket.medianResponseHours)}`
            : `${integer(bucket.closed)} cerradas · ${percent(bucket.pointCoverage)} con punto`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 20);
  });

  readonly columns: Column<ServiceRequest>[] = [
    { key: 'id', label: 'Nº', numeric: true, sortable: 'id', get: (row) => String(row.id) },
    {
      key: 'serviceName',
      label: 'Categoría',
      wide: true,
      get: (row) => row.serviceName ?? `código ${row.serviceCode}`,
      sub: (row) => (row.districtName ? `junta resuelta: ${row.districtName}` : 'sin coordenadas'),
    },
    {
      key: 'requestedAt',
      label: 'Presentada',
      sortable: 'requestedAt',
      get: (row) => date(row.requestedAt),
    },
    { key: 'status', label: 'Estado', get: (row) => (row.status === 'CLOSED' ? 'Cerrada' : 'Abierta') },
    {
      key: 'responseHours',
      label: 'Respuesta',
      numeric: true,
      get: (row) => hours(row.responseHours),
    },
  ];

  constructor() {
    this.api.citizenSummary().subscribe({
      next: (response) => {
        this.summary.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer el resumen de quejas.'),
    });
    this.loadAggregation();
    this.loadRequests();
  }

  readonly coverageByYear = computed(() => this.aggregation()?.coverageByYear ?? []);

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.loadAggregation();
  }

  setFilter(change: { key: string; value: string }): void {
    this.filters.update((current) => ({ ...current, [change.key]: change.value }));
    this.page.set(0);
    this.loadRequests();
  }

  clearFilters(): void {
    this.filters.set({ status: '', assignment: '', internal: '', from: '', to: '' });
    this.page.set(0);
    this.loadRequests();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.loadRequests();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadRequests();
  }

  private loadAggregation(): void {
    this.api.citizenAggregation(this.axis()).subscribe({
      next: (response) => this.aggregation.set(response.item),
      error: () => this.error.set('No se ha podido leer la agregación de quejas.'),
    });
  }

  private loadRequests(): void {
    const values = this.filters();
    const query: Query = {
      page: this.page(),
      size: this.size,
      sort: this.sort(),
      status: values['status'],
      assignment: values['assignment'],
      internal: values['internal'],
      from: values['from'] ? `${values['from']}T00:00:00Z` : '',
      to: values['to'] ? `${values['to']}T00:00:00Z` : '',
    };
    this.api.citizenRequests(query).subscribe({
      next: (response) => {
        this.requests.set(response.items);
        this.total.set(response.total);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'No se han podido leer las quejas.'),
    });
  }
}
