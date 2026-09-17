import { signal } from '@angular/core';
import { Observable } from 'rxjs';

import { Query } from './api';
import { ApiItem, Source } from './types';

/**
 * El estado de lo que se pide a la API, en un sitio y con tres valores.
 *
 * Las siete secciones tenían el mismo patrón copiado siete veces —señales de datos, `setFilter`, `setSort`,
 * `setPage`, `loadX`— y en ninguna de las copias existía el estado de carga: el cargando y el vacío eran lo
 * mismo. Eso no es un detalle de presentación, es una afirmación falsa sobre el dato: «Leyendo la serie…» se
 * quedaba puesto para siempre cuando la petición fallaba, y «ningún registro casa con estos filtros» aparecía
 * cuando la respuesta no había llegado. Un observatorio que dice que no hay datos donde no ha podido leer es
 * exactamente lo que no puede hacer (ADR-022 §5).
 *
 * Aquí el estado es explícito y se puede volver a intentar. No hay reintento automático: un error se enseña,
 * no se disimula, y quien mira decide si insiste.
 */
export type Status = 'loading' | 'ready' | 'failed';

/** Los valores de la barra de filtros, tal como los devuelve cada control. */
export type FilterValues = Record<string, string>;

/**
 * Por qué falló, en una frase que se pueda leer.
 *
 * Se distingue lo que la API contesta de lo que no llega a contestar: un 400 trae su `detail` y hay que
 * enseñarlo tal cual —dice qué parámetro no acepta—, mientras que un `status` 0 es que no hubo respuesta, que
 * en un frontend servido desde otro dominio suele ser la red o el CORS y no un error de datos.
 */
function reasonOf(failure: unknown): string | null {
  const response = failure as { status?: number; error?: { detail?: string; title?: string } } | null;
  if (!response) {
    return null;
  }
  if (response.status === 0) {
    return 'No hubo respuesta de la API: puede ser la red o que la instancia esté reiniciándose.';
  }
  const detail = response.error?.detail ?? response.error?.title;
  if (detail) {
    return detail;
  }
  return response.status ? `La API respondió ${response.status}.` : null;
}

/** Lo común a todo lo que se pide: en qué estado está y por qué falló. */
abstract class Requested {
  readonly status = signal<Status>('loading');
  /** El motivo, cuando lo hay. La frase de «no se ha podido leer X» la escribe quien pinta, con `what`. */
  readonly error = signal<string | null>(null);

  /** Qué es esto, en palabras y con artículo: «el resumen de quejas», «las partidas». */
  abstract readonly what: string;

  abstract reload(): void;

  protected failed(failure: unknown): void {
    this.status.set('failed');
    this.error.set(reasonOf(failure));
  }
}

/**
 * Un dato que se pide entero y de una vez: un resumen, una agregación, el cruce.
 *
 * El sobre se guarda completo porque `source`, `ingestedAt` y `caveats` son producto, no metadatos: el colofón
 * los imprime tal como llegan (ADR-020 §10).
 */
export class Loaded<T> extends Requested {
  readonly value = signal<T | null>(null);
  readonly source = signal<Source | null>(null);
  readonly ingestedAt = signal<string | null>(null);
  readonly caveats = signal<string[]>([]);

  constructor(
    private readonly request: () => Observable<ApiItem<T>>,
    readonly what: string,
    load = true,
  ) {
    super();
    if (load) {
      this.reload();
    }
  }

  override reload(): void {
    this.status.set('loading');
    this.error.set(null);
    this.request().subscribe({
      next: (response) => {
        this.value.set(response.item);
        this.source.set(response.source ?? null);
        this.ingestedAt.set(response.ingestedAt ?? null);
        this.caveats.set(response.caveats ?? []);
        this.status.set('ready');
      },
      error: (failure) => this.failed(failure),
    });
  }
}

export interface ExplorerOptions<T, R> {
  /** La llamada a la API. Recibe la consulta ya montada: página, tamaño, orden y filtros. */
  request: (query: Query) => Observable<R>;
  rows: (response: R) => T[];
  total: (response: R) => number;
  /** Orden inicial. Nunca vacío: ningún listado de esta API se pide sin criterio (regla 8). */
  sort: string;
  size?: number;
  filters?: FilterValues;
  /**
   * Cómo se traducen los filtros de la pantalla a los parámetros de la API, cuando no es uno a uno: las fechas
   * de quejas viajan como instantes, por ejemplo. Por defecto se mandan tal cual.
   */
  parameters?: (values: FilterValues) => Query;
  what: string;
}

/**
 * Un explorador de registros: la página, el orden, los filtros y la última respuesta.
 *
 * **Ordena, filtra y pagina el backend** (regla 8): cada cambio vuelve a pedir, y por eso el orden y la página
 * viven aquí y no en una lista ya descargada. Cambiar un filtro devuelve a la página 0, porque la página 7 de
 * otro filtro no existe.
 */
export class Explorer<T, R = unknown> extends Requested {
  readonly page = signal(0);
  readonly sort = signal('');
  readonly filters = signal<FilterValues>({});
  readonly response = signal<R | null>(null);
  readonly rows = signal<T[]>([]);
  readonly total = signal(0);
  readonly size: number;
  readonly what: string;

  private readonly initial: FilterValues;

  constructor(private readonly options: ExplorerOptions<T, R>) {
    super();
    this.size = options.size ?? 25;
    this.what = options.what;
    this.initial = { ...(options.filters ?? {}) };
    this.sort.set(options.sort);
    this.filters.set({ ...this.initial });
    this.reload();
  }

  setFilter(change: { key: string; value: string }): void {
    this.filters.update((current) => ({ ...current, [change.key]: change.value }));
    this.page.set(0);
    this.reload();
  }

  clearFilters(): void {
    this.filters.set({ ...this.initial });
    this.page.set(0);
    this.reload();
  }

  setSort(sort: string): void {
    this.sort.set(sort);
    this.page.set(0);
    this.reload();
  }

  setPage(page: number): void {
    this.page.set(page);
    this.reload();
  }

  override reload(): void {
    this.status.set('loading');
    this.error.set(null);
    const values = this.filters();
    const query: Query = {
      page: this.page(),
      size: this.size,
      sort: this.sort(),
      ...(this.options.parameters ? this.options.parameters(values) : values),
    };
    this.options.request(query).subscribe({
      next: (response) => {
        this.response.set(response);
        this.rows.set(this.options.rows(response));
        this.total.set(this.options.total(response));
        this.status.set('ready');
      },
      error: (failure) => this.failed(failure),
    });
  }
}
