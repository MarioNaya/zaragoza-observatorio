import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory } from '../core/api';
import { byKey, date, euro, euroShort, integer, keyOf, percent } from '../core/format';
import { Explorer, FilterValues, Loaded } from '../core/state';
import { ApiPage, Grant } from '../core/types';
import { Bar, BarChart } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { State } from '../ui/state';
import { Stat, Stats } from '../ui/stats';

type Axis = 'year' | 'beneficiary' | 'call' | 'line' | 'type' | 'manager' | 'classification';

const FILTERS: FilterValues = { q: '', year: '', classification: '', naturalPerson: '' };

const AXES: { key: Axis; label: string }[] = [
  { key: 'year', label: 'Año' },
  { key: 'beneficiary', label: 'Beneficiario' },
  { key: 'call', label: 'Convocatoria' },
  { key: 'line', label: 'Línea' },
  { key: 'type', label: 'Tipo' },
  { key: 'manager', label: 'Gestor' },
  { key: 'classification', label: 'Clasificación' },
];

/**
 * Las subvenciones. La pantalla hereda la regla que gobierna esta fuente entera (ADR-018): **del beneficiario
 * se enseña cuánto, no quién**. El eje por beneficiario ordena por importe recibido usando el seudónimo que
 * publica el propio ayuntamiento, así que se puede ver concentración sin identificar a una persona.
 */
@Component({
  selector: 'obs-grants',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon, State],
  templateUrl: './grants.html',
})
export class GrantsPage {
  private readonly api = inject(Observatory);

  readonly summary = new Loaded(() => this.api.grantsSummary(), 'el resumen de subvenciones');

  readonly axis = signal<Axis>('year');

  readonly aggregation = new Loaded(
    () => this.api.grantAggregation(this.axis()),
    'el reparto de las subvenciones',
  );

  readonly explorer = new Explorer<Grant, ApiPage<Grant>>({
    request: (query) => this.api.grants(query),
    rows: (response) => response.items,
    total: (response) => response.total,
    sort: 'granted,desc',
    filters: FILTERS,
    what: 'las concesiones',
  });

  protected readonly axes = AXES;
  protected readonly euroShort = euroShort;
  protected readonly size = 25;

  readonly filterDefs = computed<FilterDef[]>(() => [
    { key: 'q', label: 'Buscar en el título', kind: 'text', placeholder: 'deporte, cultura, vivienda…' },
    { key: 'year', label: 'Año', kind: 'number', placeholder: '2025' },
    {
      key: 'classification',
      label: 'Clasificación',
      kind: 'select',
      options: Object.keys(this.summary.value()?.byClassification ?? {})
        .filter((key) => key !== '(sin beneficiario)')
        .map((key) => ({ value: key, label: key.replace(/-/g, ' ') })),
    },
    {
      key: 'naturalPerson',
      label: 'Persona física',
      kind: 'select',
      options: [
        { value: 'false', label: 'Solo entidades' },
        { value: 'true', label: 'Solo personas físicas' },
      ],
      hint: 'Se cuentan, no se nombran',
    },
  ]);

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary.value();
    if (!summary) {
      return [];
    }
    return [
      {
        value: euroShort(summary.granted),
        label: 'Importe concedido',
        note: `En ${integer(summary.grants)} concesiones de ${summary.firstYear} a ${summary.lastYear}. Es lo acordado conceder, no lo pagado.`,
      },
      {
        value: integer(summary.beneficiaries),
        label: 'Beneficiarios distintos',
        note: `${integer(summary.naturalPersonBeneficiaries)} son personas físicas. De ellas se publica cuánto reciben y nunca quién son.`,
      },
      {
        value: integer(summary.calls),
        label: 'Convocatorias',
        note: `Con ${euroShort(summary.callBudget)} de presupuesto puesto a disposición, que es otra cosa distinta de lo repartido.`,
      },
      {
        value: integer(summary.redactedTitles),
        label: 'Títulos redactados',
        note: 'El ayuntamiento publica el DNI o el NIE dentro del texto del título. Aquí se sustituye por un marcador.',
        caution: true,
      },
      {
        value: integer(summary.withoutBeneficiary),
        label: 'Sin beneficiario',
        note: 'Son 2013 y 2014: la versión nueva de la API no los cubre y no se deduce por nombre.',
        caution: true,
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
      .map((bucket) => ({ key: keyOf(bucket), label: keyOf(bucket), value: bucket.granted }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation.value();
    if (!aggregation || aggregation.by === 'year') {
      return [];
    }
    return [...aggregation.buckets]
      .map((bucket) => ({
        key: keyOf(bucket),
        label: bucket.label ?? bucket.key ?? '(sin beneficiario)',
        value: bucket.granted,
        note:
          this.axis() === 'beneficiary'
            ? `${integer(bucket.grants)} concesiones`
            : `${integer(bucket.grants)} concesiones · ${integer(bucket.beneficiaries)} beneficiarios`,
      }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 18);
  });

  readonly columns: Column<Grant>[] = [
    {
      key: 'title',
      label: 'Concesión',
      wide: true,
      get: (row) => row.title ?? '(sin título)',
      sub: (row) => row.call ?? null,
    },
    {
      key: 'beneficiary',
      label: 'Beneficiario',
      get: (row) => row.beneficiary ?? '(no publicado)',
      sub: (row) => (row.naturalPerson ? 'persona física: se cuenta, no se nombra' : null),
    },
    { key: 'grantedOn', label: 'Concedida', sortable: 'grantedOn', get: (row) => date(row.grantedOn) },
    {
      key: 'requested',
      label: 'Solicitado',
      numeric: true,
      sortable: 'requested',
      get: (row) => euro(row.requested),
    },
    { key: 'granted', label: 'Concedido', numeric: true, sortable: 'granted', get: (row) => euro(row.granted) },
  ];

  readonly naturalShare = computed(() => {
    const summary = this.summary.value();
    return summary ? percent(summary.naturalPersonGrants / summary.grants) : '—';
  });

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.aggregation.reload();
  }
}
