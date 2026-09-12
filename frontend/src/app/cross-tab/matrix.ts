import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { CrossTab, MEASURE_LABELS, MeasureColumn, UNIT_LABELS } from '../api/types';

/**
 * La matriz: una fila por junta y una columna por medida (ADR-019 §1).
 *
 * Dos reglas la gobiernan y las dos son de ADR-020. La cabecera **vuelve a pedir** a la API en vez de ordenar
 * el array que ya tiene (§12), y cada celda enseña **las dos cifras** —el recuento y la tasa— porque una tasa
 * sola esconde que tres y trescientas pueden acabar del mismo color (§8).
 */
@Component({
  selector: 'obs-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './matrix.css',
  template: `
    <table>
      <caption class="sr-only">
        Las {{ tab().districts }} juntas municipales y vecinales por las medidas pedidas
      </caption>
      <thead>
        <tr>
          <th scope="col" class="district">
            <button type="button" (click)="sortBy.emit('district')" [class.active]="sortField() === 'district'">
              Junta
              <span class="arrow">{{ arrowFor('district') }}</span>
            </button>
          </th>
          @for (measure of tab().measures; track measure.id) {
            <th scope="col" class="numeric">
              <button type="button" (click)="sortBy.emit(measure.id)" [class.active]="sortField() === measure.id">
                {{ label(measure) }}
                <span class="arrow">{{ arrowFor(measure.id) }}</span>
              </button>
              <span class="unit">{{ unit(measure) }}</span>
            </th>
          }
          <th scope="col" class="numeric denominator">Padrón</th>
        </tr>
      </thead>
      <tbody>
        @for (row of tab().items; track row.districtId) {
          <tr [class.selected]="row.districtId === selected()" (click)="districtPicked.emit(row.districtId)">
            <th scope="row" class="district">{{ row.shortName }}</th>
            @for (measure of tab().measures; track measure.id) {
              <td class="numeric">
                <span class="count">{{ integer(row.values[measure.id]) }}</span>
                @if (showRates()) {
                  @if (row.perThousandInhabitants[measure.id] !== undefined) {
                    <span class="rate">{{ decimal(row.perThousandInhabitants[measure.id]) }} ‰</span>
                  } @else {
                    <span class="rate missing" title="El padrón no publica ese año">sin denominador</span>
                  }
                }
              </td>
            }
            <td class="numeric denominator">
              @if (row.population !== null) {
                {{ integer(row.population) }}
                <span class="year">{{ row.populationYear }}</span>
              } @else {
                <span class="missing">—</span>
              }
            </td>
          </tr>
        }
      </tbody>
      <tfoot>
        <tr>
          <th scope="row" class="district">Suma de las {{ tab().districts }} juntas</th>
          @for (measure of tab().measures; track measure.id) {
            <td class="numeric">
              <span class="count">{{ integer(measure.coverage.assigned) }}</span>
              <span class="rate">de {{ integer(measure.coverage.total) }}</span>
            </td>
          }
          <td class="numeric denominator"></td>
        </tr>
      </tfoot>
    </table>

    <!-- El pie no es decorativo: es la diferencia entre lo que suma la tabla y lo que hay (ADR-019 §6). -->
    <p class="note">
      La suma de las filas es lo <strong>asignado</strong>, no el total: solo se puede situar en una junta lo que
      trae punto, y no se geocodifica ninguna dirección para rellenar el hueco.
    </p>
  `,
})
export class Matrix {
  readonly tab = input.required<CrossTab>();
  readonly selected = input<number | null>(null);
  readonly showRates = input.required<boolean>();

  readonly sortBy = output<string>();
  readonly districtPicked = output<number>();

  readonly sortField = computed(() => this.tab().sort.split(',')[0]);
  private readonly descending = computed(() => this.tab().sort.split(',')[1] === 'desc');

  protected arrowFor(field: string): string {
    if (this.sortField() !== field) {
      return '';
    }
    return this.descending() ? '▾' : '▴';
  }

  protected label(measure: MeasureColumn): string {
    return MEASURE_LABELS[measure.id] ?? measure.id;
  }

  protected unit(measure: MeasureColumn): string {
    return UNIT_LABELS[measure.unit] ?? measure.unit;
  }

  protected integer(value: number): string {
    return value.toLocaleString('es-ES', { maximumFractionDigits: 0 });
  }

  protected decimal(value: number): string {
    return value.toLocaleString('es-ES', { maximumFractionDigits: 1 });
  }
}
