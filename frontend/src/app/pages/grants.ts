import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory, Query } from '../core/api';
import { byKey, date, euro, euroShort, integer, keyOf, percent } from '../core/format';
import { Grant, GrantAggregation, GrantsSummary, Source } from '../core/types';
import { BarChart, Bar } from '../ui/bar-chart';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, FilterValues, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { Stat, Stats } from '../ui/stats';

type Axis = 'year' | 'beneficiary' | 'call' | 'line' | 'type' | 'manager' | 'classification';

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
  imports: [Stats, BarChart, Ranking, DataTable, Filters, Colophon],
  templateUrl: './grants.html',
})
export class GrantsPage {
  private readonly api = inject(Observatory);

  readonly summary = signal<GrantsSummary | null>(null);
  readonly aggregation = signal<GrantAggregation | null>(null);
  readonly grants = signal<Grant[]>([]);
  readonly total = signal(0);
  readonly caveats = signal<string[]>([]);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly axis = signal<Axis>('year');
  readonly page = signal(0);
  readonly sort = signal('granted,desc');
  readonly filters = signal<FilterValues>({ q: '', year: '', classification: '', naturalPerson: '' });

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
      options: Object.keys(this.summary()?.byClassification ?? {})
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
    const summary = this.summary();
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
    const aggregation = this.aggregation();
    if (!aggregation || aggregation.by !== 'year') {
      return [];
    }
    return [...aggregation.buckets]
      .sort(byKey)
      .map((bucket) => ({ key: keyOf(bucket), label: keyOf(bucket), value: bucket.granted }));
  });

  readonly ranking = computed<RankRow[]>(() => {
    const aggregation = this.aggregation();
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

  constructor() {
    this.api.grantsSummary().subscribe({
      next: (response) => {
        this.summary.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer el resumen de subvenciones.'),
    });
    this.loadAggregation();
    this.loadGrants();
  }

  readonly naturalShare = computed(() => {
    const summary = this.summary();
    return summary ? percent(summary.naturalPersonGrants / summary.grants) : '—';
  });

  setAxis(axis: Axis): void {
    this.axis.set(axis);
    this.loadAggregation();
  }

  setFilter(change: { key: string; value: string }): void {
    this.filters.update((current) => ({ ...current, [change.key]: change.value }));
    this.page.set(0);
    this.loadGrants();
  }

  clearFilters(): void {
    this.filters.set({ q: '', year: '', classification: '', naturalPerson: '' });
    this.page.set(0);
    this.loadGrants();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.loadGrants();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.loadGrants();
  }

  private loadAggregation(): void {
    this.api.grantAggregation(this.axis()).subscribe({
      next: (response) => this.aggregation.set(response.item),
      error: () => this.error.set('No se ha podido leer la agregación de subvenciones.'),
    });
  }

  private loadGrants(): void {
    const query: Query = { page: this.page(), size: this.size, sort: this.sort(), ...this.filters() };
    this.api.grants(query).subscribe({
      next: (response) => {
        this.grants.set(response.items);
        this.total.set(response.total);
      },
      error: (failure) => this.error.set(failure?.error?.detail ?? 'No se han podido leer las concesiones.'),
    });
  }
}
