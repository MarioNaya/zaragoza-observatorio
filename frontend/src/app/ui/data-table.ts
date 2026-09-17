import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** Una columna. `get` saca el texto ya formateado: la tabla no sabe formatear nada. */
export interface Column<T> {
  key: string;
  label: string;
  get: (row: T) => string;
  /** Campo de `sort` de la API. Sin él la cabecera no es pulsable, y eso se ve. */
  sortable?: string;
  numeric?: boolean;
  /** Texto secundario bajo la celda: el matiz que no cabe en una columna aparte. */
  sub?: (row: T) => string | null;
  wide?: boolean;
}

/**
 * El explorador de registros. Es la pieza que convierte la API en una herramienta: paginar y ordenar sobre
 * cualquiera de los seis recursos que la API pagina.
 *
 * No abre la ficha de un registro: la tabla llevaba un `pickable`/`picked` que **ninguna página conectó nunca**
 * y que la auditoría quitó, porque una capacidad que no se usa no se prueba y engaña al que lee el código. El
 * detalle de un registro sigue pendiente y, cuando entre, entra con su ruta (ADR-022 §7).
 *
 * **Ordena y pagina el backend, siempre** (regla 8, `SPEC.md` §4.9): pulsar una cabecera emite el `sort` y
 * quien lo recibe vuelve a pedir. Nunca se reordena el array que ya está en memoria, porque entonces la
 * ordenación sería solo de la página visible y mentiría.
 */
@Component({
  selector: 'obs-data-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './data-table.css',
  template: `
    <div class="scroll">
      <table>
        <caption class="sr-only">{{ caption() }}</caption>
        <thead>
          <tr>
            @for (column of columns(); track column.key) {
              <th
                scope="col"
                [class.numeric]="column.numeric"
                [class.wide]="column.wide"
                [attr.aria-sort]="ariaSort(column)"
              >
                @if (column.sortable) {
                  <button type="button" [class.on]="field() === column.sortable" (click)="sortBy(column)">
                    {{ column.label }}<span class="arrow">{{ arrow(column) }}</span>
                  </button>
                } @else {
                  {{ column.label }}
                }
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track $index) {
            <tr>
              @for (column of columns(); track column.key) {
                <td [class.numeric]="column.numeric" [class.wide]="column.wide">
                  <span class="cell" [class.num]="column.numeric">{{ column.get(row) }}</span>
                  @if (column.sub && column.sub(row); as sub) {
                    <span class="sub">{{ sub }}</span>
                  }
                </td>
              }
            </tr>
          } @empty {
            <tr class="empty">
              <td [attr.colspan]="columns().length">Ningún registro casa con estos filtros.</td>
            </tr>
          }
        </tbody>
      </table>
    </div>

    <nav class="pager" [attr.aria-label]="'Paginación de ' + caption()">
      <p class="count">
        @if (total() > 0) {
          <span class="num">{{ from() }}–{{ to() }}</span> de <span class="num">{{ totalText() }}</span>
          {{ unit() }}
        } @else {
          Sin resultados
        }
      </p>
      <div class="buttons">
        <button type="button" [disabled]="page() === 0" (click)="go(0)" title="Primera página">«</button>
        <button type="button" [disabled]="page() === 0" (click)="go(page() - 1)">Anterior</button>
        <span class="of">
          Página <span class="num">{{ page() + 1 }}</span> de <span class="num">{{ pages() }}</span>
        </span>
        <button type="button" [disabled]="page() + 1 >= pages()" (click)="go(page() + 1)">Siguiente</button>
        <button type="button" [disabled]="page() + 1 >= pages()" (click)="go(pages() - 1)" title="Última página">
          »
        </button>
      </div>
    </nav>
  `,
})
export class DataTable<T> {
  readonly rows = input.required<T[]>();
  readonly columns = input.required<Column<T>[]>();
  readonly total = input.required<number>();
  readonly page = input.required<number>();
  readonly size = input.required<number>();
  readonly sort = input<string>('');
  readonly unit = input('registros');
  readonly caption = input('Registros');

  readonly sortChanged = output<string>();
  readonly pageChanged = output<number>();

  readonly field = computed(() => this.sort().split(',')[0]);
  private readonly descending = computed(() => this.sort().split(',')[1] === 'desc');

  readonly pages = computed(() => Math.max(1, Math.ceil(this.total() / Math.max(1, this.size()))));
  readonly from = computed(() => (this.total() === 0 ? 0 : this.page() * this.size() + 1));
  readonly to = computed(() => Math.min(this.total(), (this.page() + 1) * this.size()));
  readonly totalText = computed(() => this.total().toLocaleString('es-ES'));

  protected arrow(column: Column<T>): string {
    if (this.field() !== column.sortable) {
      return '';
    }
    return this.descending() ? ' ▾' : ' ▴';
  }

  protected ariaSort(column: Column<T>): string | null {
    if (!column.sortable || this.field() !== column.sortable) {
      return null;
    }
    return this.descending() ? 'descending' : 'ascending';
  }

  protected sortBy(column: Column<T>): void {
    if (!column.sortable) {
      return;
    }
    const same = this.field() === column.sortable;
    // Una columna nueva empieza por lo más grande, que es lo que suele buscarse; repetir invierte.
    const direction = same ? (this.descending() ? 'asc' : 'desc') : column.numeric ? 'desc' : 'asc';
    this.sortChanged.emit(`${column.sortable},${direction}`);
  }

  protected go(page: number): void {
    this.pageChanged.emit(Math.min(this.pages() - 1, Math.max(0, page)));
  }
}
