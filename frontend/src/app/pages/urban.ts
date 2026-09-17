import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory } from '../core/api';
import { byKey, date, integer, keyOf, labelOf, percent } from '../core/format';
import { Explorer, FilterValues, Loaded } from '../core/state';
import { ApiPage, Premises } from '../core/types';
import { Bar, BarChart } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { State } from '../ui/state';
import { Stat, Stats } from '../ui/stats';

type Axis = 'activity' | 'licence_year' | 'district' | 'licence_type' | 'status';

const FILTERS: FilterValues = { iaeSection: '', statusCode: '', assignment: '', licenceYear: '' };

/**
 * Actividad urbana privada: locales con licencia y sus licencias. Es la fuente territorial con **mejor
 * cobertura** del producto, en torno al 90 %, y la que obliga a decir a cada paso **qué unidad se está
 * contando**: un local con doce licencias es un local y son doce licencias (ADR-016).
 */
@Component({
  selector: 'obs-urban',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon, State],
  templateUrl: './urban.html',
})
export class UrbanPage {
  private readonly api = inject(Observatory);

  readonly summary = new Loaded(() => this.api.urbanSummary(), 'el resumen de actividad urbana');

  readonly axis = signal<Axis>('activity');

  readonly aggregation = new Loaded(
    () => this.api.urbanAggregation(this.axis()),
    'el reparto de la actividad urbana',
  );

  readonly explorer = new Explorer<Premises, ApiPage<Premises>>({
    request: (query) => this.api.urbanPremises(query),
    rows: (response) => response.items,
    total: (response) => response.total,
    sort: 'createdAt,desc',
    filters: FILTERS,
    what: 'los locales',
  });

  protected readonly integer = integer;
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
      // Los cuatro estados de `geo.Assignment`, AMBIGUOUS incluido: los 29 polígonos se solapan en Juslibol y
      // esa ambigüedad se registra, no se resuelve en silencio (ADR-011). Si no se puede pedir, no se ve.
      options: [
        { value: 'RESOLVED', label: 'Situado en una junta' },
        { value: 'AMBIGUOUS', label: 'En dos juntas a la vez' },
        { value: 'NO_POINT', label: 'Sin coordenadas' },
        { value: 'OUTSIDE', label: 'Fuera del término' },
      ],
    },
    { key: 'licenceYear', label: 'Año de licencia', kind: 'number', placeholder: '2024' },
  ];

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary.value();
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

  readonly unit = computed(() => this.aggregation.value()?.unit ?? 'premises');

  readonly bars = computed<Bar[]>(() => {
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by !== 'licence_year') {
      return [];
    }
    return (
      [...aggregation.buckets]
        .filter((bucket) => Number(bucket.key) >= 1990)
        .sort(byKey)
        // `total` es el recuento en la unidad que la respuesta declara. `licences || premises` cambiaba de
        // unidad en cuanto un año tenía cero licencias, y eso es enseñar locales llamándolos licencias.
        .map((bucket) => ({ key: keyOf(bucket), label: keyOf(bucket), value: bucket.total }))
    );
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by === 'licence_year') {
      return [];
    }
    const licences = aggregation.unit === 'licences';
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: labelOf(bucket),
        value: bucket.total,
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

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.aggregation.reload();
  }
}
