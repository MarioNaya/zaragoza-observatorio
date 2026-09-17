import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory } from '../core/api';
import { byKey, date, hours, integer, keyOf, labelOf, month, percent } from '../core/format';
import { Explorer, FilterValues, Loaded } from '../core/state';
import {
  ApiPage,
  CitizenAggregation,
  CitizenSummary,
  ServiceRequest,
  YearCoverage,
} from '../core/types';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, Filters } from '../ui/filters';
import { LineChart, Series } from '../ui/line-chart';
import { Ranking, RankRow } from '../ui/ranking';
import { State } from '../ui/state';
import { Stat, Stats } from '../ui/stats';

type Axis = 'month' | 'category' | 'district';

const FILTERS: FilterValues = { status: '', assignment: '', internal: '', from: '', to: '' };

/**
 * Quejas y sugerencias. Dos hechos gobiernan la lectura y van escritos en la propia pantalla: **el texto de la
 * queja no está aquí** porque no se pide al origen (ADR-012), y **solo tres de cada diez se pueden situar** en
 * una junta, así que todo lo territorial de esta fuente se lee con su cobertura al lado.
 */
@Component({
  selector: 'obs-citizen',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, LineChart, Ranking, DataTable, Filters, Colophon, State],
  templateUrl: './citizen.html',
})
export class CitizenPage {
  private readonly api = inject(Observatory);

  readonly summary = new Loaded(() => this.api.citizenSummary(), 'el resumen de quejas');

  readonly axis = signal<Axis>('month');

  readonly aggregation = new Loaded(
    () => this.api.citizenAggregation(this.axis()),
    'el reparto de quejas',
  );

  readonly explorer = new Explorer<ServiceRequest, ApiPage<ServiceRequest>>({
    request: (query) => this.api.citizenRequests(query),
    rows: (response) => response.items,
    total: (response) => response.total,
    sort: 'requestedAt,desc',
    filters: FILTERS,
    // Las dos fechas se piden como instantes: la API los espera así y el control da un día suelto.
    parameters: (values) => ({
      ...values,
      from: values['from'] ? `${values['from']}T00:00:00Z` : '',
      to: values['to'] ? `${values['to']}T00:00:00Z` : '',
    }),
    what: 'las quejas',
  });

  /**
   * La cobertura de punto de cada año entero, que es la cifra que permite comparar dos años (ADR-015).
   *
   * **No viene con el eje por junta**: la API la manda solo en el eje de serie `district_year`, así que se pide
   * aparte y solo cuando hace falta. Antes se leía del mismo cuerpo que el reparto por junta, donde llega
   * siempre vacía a propósito, y el panel entero no se pintaba nunca aunque la pantalla lo prometiera dos
   * párrafos antes.
   */
  readonly coverage = new Loaded(
    () => this.api.citizenAggregation('district_year'),
    'la cobertura por año',
    false,
  );

  protected readonly integer = integer;
  protected readonly percent = percent;
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
      // Los cuatro estados que la API publica, no los dos que se esperan: hay un REJECTED y un UNKNOWN, y un
      // filtro que no los ofrece deja dos registros fuera del alcance de quien mira.
      options: [
        { value: 'OPEN', label: 'Abiertas' },
        { value: 'CLOSED', label: 'Cerradas' },
        { value: 'REJECTED', label: 'Rechazadas' },
        { value: 'UNKNOWN', label: 'Sin estado reconocible' },
      ],
    },
    {
      key: 'assignment',
      label: 'Situación territorial',
      kind: 'select',
      options: [
        { value: 'RESOLVED', label: 'Situada en una junta' },
        { value: 'AMBIGUOUS', label: 'En dos juntas a la vez' },
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
    const summary = this.summary.value();
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
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by !== 'month') {
      return [];
    }
    const ordered = [...aggregation.buckets].sort(byKey);
    return [
      {
        name: 'Quejas presentadas',
        points: ordered.map((bucket) => ({
          key: keyOf(bucket),
          label: month(keyOf(bucket)),
          value: bucket.total,
        })),
      },
    ];
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by === 'month') {
      return [];
    }
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: labelOf(bucket),
        value: bucket.total,
        note:
          aggregation.by === 'district'
            ? `${integer(bucket.closed)} cerradas · mediana de respuesta ${hours(bucket.medianResponseHours)}`
            : `${integer(bucket.closed)} cerradas · ${percent(bucket.pointCoverage)} con punto`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 20);
  });

  readonly coverageByYear = computed<YearCoverage[]>(() => this.coverage.value()?.coverageByYear ?? []);

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

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.aggregation.reload();
    if (axis === 'district' && this.coverage.value() === null) {
      this.coverage.reload();
    }
  }
}
