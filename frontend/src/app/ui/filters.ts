import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

export type FilterKind = 'text' | 'select' | 'date' | 'number';

export interface FilterDef {
  key: string;
  label: string;
  kind: FilterKind;
  /** Para `select`. La opción vacía («Todas») la pone el componente. */
  options?: { value: string; label: string }[];
  placeholder?: string;
  hint?: string;
}

export type FilterValues = Record<string, string>;

/**
 * La barra de filtros: una fila encima del contenido, como pide la guía de interacción.
 *
 * Es declarativa a propósito. Cada sección describe sus filtros con los parámetros que **la API acepta de
 * verdad** —leídos de `/v3/api-docs`, no inventados— y esta barra los pinta y los devuelve. Así ningún
 * filtro de la pantalla puede existir sin que exista en el backend, que es lo que impide filtrar en el
 * navegador sobre una página suelta y llamarlo filtrar.
 */
@Component({
  selector: 'obs-filters',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './filters.css',
  template: `
    <form class="filters" (submit)="$event.preventDefault()" role="search">
      @for (filter of defs(); track filter.key) {
        <label [class.wide]="filter.kind === 'text'">
          <span class="label">{{ filter.label }}</span>

          @switch (filter.kind) {
            @case ('select') {
              <select
                [value]="values()[filter.key]"
                (change)="set(filter.key, $any($event.target).value)"
              >
                <option value="">Todas</option>
                @for (option of filter.options ?? []; track option.value) {
                  <option [value]="option.value">{{ option.label }}</option>
                }
              </select>
            }
            @case ('date') {
              <input
                type="date"
                [value]="values()[filter.key]"
                (change)="set(filter.key, $any($event.target).value)"
              />
            }
            @case ('number') {
              <input
                type="number"
                [value]="values()[filter.key]"
                [placeholder]="filter.placeholder ?? ''"
                (change)="set(filter.key, $any($event.target).value)"
              />
            }
            @default {
              <input
                type="search"
                [value]="values()[filter.key]"
                [placeholder]="filter.placeholder ?? 'Buscar…'"
                (search)="set(filter.key, $any($event.target).value)"
                (change)="set(filter.key, $any($event.target).value)"
              />
            }
          }

          @if (filter.hint) {
            <span class="hint">{{ filter.hint }}</span>
          }
        </label>
      }

      @if (active().length > 0) {
        <button type="button" class="clear" (click)="cleared.emit()">
          Quitar {{ active().length }} filtro{{ active().length === 1 ? '' : 's' }}
        </button>
      }
    </form>
  `,
})
export class Filters {
  readonly defs = input.required<FilterDef[]>();
  readonly values = input.required<FilterValues>();

  readonly changed = output<{ key: string; value: string }>();
  readonly cleared = output<void>();

  readonly active = computed(() => Object.entries(this.values()).filter(([, value]) => value !== ''));

  protected set(key: string, value: string): void {
    this.changed.emit({ key, value: value ?? '' });
  }
}
