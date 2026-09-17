import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { Observatory } from '../core/api';
import { date, integer, percent } from '../core/format';
import { Explorer, FilterValues, Loaded } from '../core/state';
// El sobre del catálogo y esta pantalla se llaman igual, así que el tipo entra con otro nombre.
import { CatalogPage as CatalogEnvelope, Dataset } from '../core/types';
import { Colophon } from '../ui/colophon';
import { Column, DataTable } from '../ui/data-table';
import { FilterDef, Filters } from '../ui/filters';
import { Ranking, RankRow } from '../ui/ranking';
import { State } from '../ui/state';
import { Stat, Stats } from '../ui/stats';

const FILTERS: FilterValues = { q: '', freshness: '', observation: '', open: '', listed: '' };

const FRESHNESS_LABELS: Record<string, string> = {
  ON_TIME: 'Al día',
  SLIGHT_DELAY: 'Ligero retraso',
  DELAYED: 'Retrasado',
  NOT_UPDATED: 'Sin actualizar',
  NOT_EVALUABLE: 'No evaluable',
};

const METHOD_LABELS: Record<string, string> = {
  FILE_HEADERS: 'Cabeceras del fichero',
  API_MAX_DATE: 'Fecha máxima en la API',
  API_COUNT: 'Solo recuento',
  WFS_HITS: 'Recuento WFS',
  NOT_OBSERVABLE: 'No observable',
};

/**
 * El monitor de frescura del catálogo: 437 fichas municipales con **dos ejes que no se mezclan**.
 *
 * El declarado compara lo que el publicador dice (`modified` y periodicidad) y el observado pregunta a diario
 * a una distribución de cada ficha y publica lo que devuelve con el método que usó. **No hay categoría
 * observada** y no se cruzan: eso exigiría una serie larga y una decisión, y ninguna de las dos existe todavía
 * (ADR-005).
 */
@Component({
  selector: 'obs-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Stats, Ranking, DataTable, Filters, Colophon, State],
  templateUrl: './catalog.html',
})
export class CatalogPage {
  private readonly api = inject(Observatory);

  readonly summary = new Loaded(() => this.api.catalogSummary(), 'el resumen del catálogo');

  readonly explorer = new Explorer<Dataset, CatalogEnvelope<Dataset>>({
    request: (query) => this.api.datasets(query),
    rows: (response) => response.items,
    // El catálogo pagina con un objeto `page`, no con campos sueltos como el resto de la API.
    total: (response) => response.page.totalElements,
    sort: 'title,asc',
    filters: FILTERS,
    what: 'las fichas del catálogo',
  });

  protected readonly integer = integer;

  readonly filterDefs: FilterDef[] = [
    { key: 'q', label: 'Buscar ficha', kind: 'text', placeholder: 'tráfico, padrón, contenedores…' },
    {
      key: 'freshness',
      label: 'Frescura declarada',
      kind: 'select',
      options: Object.entries(FRESHNESS_LABELS).map(([value, label]) => ({ value, label })),
    },
    {
      key: 'observation',
      label: 'Método observado',
      kind: 'select',
      options: Object.entries(METHOD_LABELS).map(([value, label]) => ({ value, label })),
    },
    {
      key: 'open',
      label: 'Formato abierto',
      kind: 'select',
      options: [
        { value: 'true', label: 'Abierto' },
        { value: 'false', label: 'No abierto' },
      ],
    },
    {
      key: 'listed',
      label: 'Sigue en el listado',
      kind: 'select',
      options: [
        { value: 'true', label: 'Sí' },
        { value: 'false', label: 'Ya no aparece' },
      ],
    },
  ];

  readonly stats = computed<Stat[]>(() => {
    const summary = this.summary.value();
    if (!summary) {
      return [];
    }
    const notEvaluable = summary.byDeclaredFreshness['NOT_EVALUABLE'] ?? 0;
    return [
      {
        value: integer(summary.datasets),
        label: 'Fichas vigiladas',
        note: `Con instantánea diaria. La última se tomó el ${summary.latestSnapshotOn}.`,
      },
      {
        value: percent(notEvaluable / summary.datasets),
        label: 'No evaluables',
        note: `${integer(notEvaluable)} fichas no declaran periodicidad utilizable o no traen fecha de modificación. Es el hallazgo del monitor, no un fallo suyo.`,
        caution: true,
      },
      {
        value: integer(summary.byObservationMethod['NOT_OBSERVABLE'] ?? 0),
        label: 'Sin nada que observar',
        note: 'Ninguna de sus distribuciones responde a una petición que permita medir algo.',
      },
      {
        value: integer(summary.notListed),
        label: 'Dadas de baja',
        note: 'Dejaron de aparecer en el listado municipal. No se borran: su histórico es justamente lo que nadie más guarda.',
      },
      {
        value: integer(Number(summary.federation['notInCatalog'] ?? 0)),
        label: 'Federadas sin ficha',
        note: 'Están en datos.gob.es y el listado municipal no las devuelve: son partes de series y colecciones.',
      },
    ];
  });

  readonly byFreshness = computed<RankRow[]>(() => {
    const summary = this.summary.value();
    if (!summary) {
      return [];
    }
    return Object.entries(summary.byDeclaredFreshness).map(([key, value]) => ({
      key,
      label: FRESHNESS_LABELS[key] ?? key,
      value: value ?? 0,
    }));
  });

  readonly byMethod = computed<RankRow[]>(() => {
    const summary = this.summary.value();
    if (!summary) {
      return [];
    }
    return Object.entries(summary.byObservationMethod).map(([key, value]) => ({
      key,
      label: METHOD_LABELS[key] ?? key,
      value: value ?? 0,
    }));
  });

  readonly columns: Column<Dataset>[] = [
    {
      key: 'title',
      label: 'Ficha',
      wide: true,
      sortable: 'title',
      get: (row) => row.title,
      sub: (row) => {
        const method = row.latestObservationMethod;
        const parts = [method ? (METHOD_LABELS[method] ?? method) : null];
        if (row.delistedAt) {
          parts.push(`dada de baja el ${date(row.delistedAt)}`);
        }
        return parts.filter(Boolean).join(' · ') || null;
      },
    },
    {
      key: 'declaredPeriodicity',
      label: 'Periodicidad',
      get: (row) => row.declaredPeriodicity ?? '—',
    },
    {
      key: 'declaredModified',
      label: 'Dice que cambió',
      sortable: 'declaredModified',
      get: (row) => date(row.declaredModified),
    },
    {
      key: 'observedLastChange',
      label: 'Observamos cambio',
      // El campo del cuerpo y el del `sort` no se llaman igual, y el de la respuesta es el de `latest`.
      sortable: 'observedLastChange',
      get: (row) => date(row.latestObservedChange),
    },
    {
      key: 'freshness',
      label: 'Frescura declarada',
      get: (row) => {
        const key = row.latestFreshness;
        return key ? (FRESHNESS_LABELS[key] ?? key) : '—';
      },
    },
  ];
}
