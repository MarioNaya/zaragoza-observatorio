import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { DistrictCard as Card, MEASURE_LABELS, UNIT_LABELS } from '../api/types';

/**
 * La ficha de una junta (ADR-019 §1): la misma fila del cruce con la **serie de padrón entera** al lado.
 *
 * La serie se enseña completa y con su hueco: el padrón por junta tiene 2020, 2021, 2022 y 2024, y no 2023.
 * Que falte un año es un hecho de la fuente y se ve; interpolarlo sería inventar población (ADR-015).
 */
@Component({
  selector: 'obs-district-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './district-card.css',
  template: `
    <aside>
      <header>
        <div>
          <h2>{{ card().item.shortName }}</h2>
          <p class="ids">
            Junta {{ card().item.districtId }}
            @if (card().item.padronId !== null) {
              · padrón {{ card().item.padronId }}
            }
          </p>
        </div>
        <button type="button" class="close" (click)="closed.emit()" aria-label="Cerrar la ficha">×</button>
      </header>

      <dl class="measures">
        @for (measure of card().measures; track measure.id) {
          <div>
            <dt>{{ label(measure.id) }}</dt>
            <dd>
              <span class="value">{{ integer(card().item.values[measure.id]) }}</span>
              <span class="unit">{{ unit(measure.unit) }}</span>
              @if (card().item.perThousandInhabitants[measure.id] !== undefined) {
                <span class="rate">
                  {{ decimal(card().item.perThousandInhabitants[measure.id]) }} por mil habitantes
                </span>
              }
              <span class="read">
                Origen leído {{ readAt(measure.ingestedAt) }}. La ventana cae sobre {{ measure.dateFieldMeaning }}.
              </span>
            </dd>
          </div>
        }
      </dl>

      <section class="series">
        <h3>Padrón publicado</h3>
        @if (series().length > 0) {
          <ol>
            @for (year of series(); track year.year) {
              <li>
                <span class="bar" [style.width.%]="year.share"></span>
                <span class="year">{{ year.year }}</span>
                <span class="people">{{ integer(year.population) }}</span>
              </li>
            }
          </ol>
          <p class="gap">
            La serie es la que publica el ayuntamiento y <strong>no es continua</strong>: falta 2023. Los años
            que no están no se interpolan.
          </p>
        } @else {
          <p class="gap">Esta junta no tiene padrón publicado en ningún año.</p>
        }
      </section>
    </aside>
  `,
})
export class DistrictCardView {
  readonly card = input.required<Card>();
  readonly closed = output<void>();

  readonly series = computed(() => {
    const records = [...this.card().populationSeries].sort((a, b) => a.year - b.year);
    const max = records.reduce((top, record) => Math.max(top, record.population), 0);
    return records.map((record) => ({
      ...record,
      share: max > 0 ? (record.population / max) * 100 : 0,
    }));
  });

  protected label(id: string): string {
    return MEASURE_LABELS[id as keyof typeof MEASURE_LABELS] ?? id;
  }

  protected unit(unit: string): string {
    return UNIT_LABELS[unit] ?? unit;
  }

  protected integer(value: number): string {
    return value.toLocaleString('es-ES', { maximumFractionDigits: 0 });
  }

  protected decimal(value: number): string {
    return value.toLocaleString('es-ES', { maximumFractionDigits: 1 });
  }

  protected readAt(instant: string | null): string {
    if (!instant) {
      return 'nunca';
    }
    return new Date(instant).toLocaleString('es-ES', { dateStyle: 'medium', timeStyle: 'short' });
  }
}
