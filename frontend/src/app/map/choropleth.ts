import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { DistrictFeature, DistrictRow, MeasureColumn } from '../api/types';
import { Classification, classOf, CLASS_COUNT } from './classification';
import { boundsOf, labelPointOf, pathOf, projectionFor } from './projection';

interface Shape {
  districtId: number;
  name: string;
  path: string;
  label: { x: number; y: number };
  /** La clase de color, o `null` cuando la junta no tiene valor que pintar. */
  klass: number | null;
  value: number | null;
  formatted: string;
}

/**
 * El mapa: los 29 polígonos oficiales en SVG y **ningún fondo de teselas** (ADR-020 §4).
 *
 * `measure` es obligatorio y no por comodidad: lleva dentro la cobertura de la columna, y ADR-020 §9 exige que
 * no exista forma de dibujar este mapa sin ella. La leyenda que la escribe es parte del mismo componente por la
 * misma razón —separarlas permitiría pintar uno sin la otra—.
 */
@Component({
  selector: 'obs-choropleth',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './choropleth.css',
  template: `
    <figure class="map">
      <svg
        [attr.viewBox]="'0 0 ' + width() + ' ' + height()"
        [attr.aria-label]="'Mapa de ' + title() + ' por junta'"
        role="img"
      >
        @for (shape of shapes(); track shape.districtId) {
          <path
            [attr.d]="shape.path"
            [class]="'district ' + (shape.klass === null ? 'no-data' : 'c' + shape.klass)"
            [class.selected]="shape.districtId === selected()"
            [attr.aria-label]="shape.name + ': ' + shape.formatted"
            tabindex="0"
            (click)="districtPicked.emit(shape.districtId)"
            (keydown.enter)="districtPicked.emit(shape.districtId)"
          >
            <title>{{ shape.name }} — {{ shape.formatted }}</title>
          </path>
        }
        @for (shape of shapes(); track shape.districtId) {
          <text class="label" [attr.x]="shape.label.x" [attr.y]="shape.label.y">{{ shape.name }}</text>
        }
      </svg>

      <figcaption class="legend">
        <div class="scale">
          <span class="title">{{ title() }}</span>
          <ol class="swatches">
            @for (bin of bins(); track $index) {
              <li>
                <span class="swatch" [class]="'c' + $index"></span>
                <span class="range">{{ bin.range }}</span>
                <span class="count">{{ bin.count }} {{ bin.count === 1 ? 'junta' : 'juntas' }}</span>
              </li>
            }
          </ol>
          <p class="method">
            Clasificación: <strong>{{ methodLabel() }}</strong>. Los cortes son los de arriba y cambian con el
            método.
          </p>
        </div>

        <!--
          La cobertura, siempre visible y pegada a la columna que se está pintando (ADR-020 §9). No se puede
          pintar por polígono: dentro de una junta vale 1 por construcción, porque sin punto no hay junta.
        -->
        <p class="coverage" [class.poor]="isPoor()">
          {{ coverageSentence() }}
        </p>
      </figcaption>
    </figure>
  `,
})
export class Choropleth {
  readonly features = input.required<DistrictFeature[]>();
  readonly rows = input.required<DistrictRow[]>();
  /** Obligatorio: trae la cobertura sin la cual este mapa no se dibuja (ADR-020 §9). */
  readonly measure = input.required<MeasureColumn>();
  readonly classification = input.required<Classification>();
  readonly methodLabel = input.required<string>();
  readonly title = input.required<string>();
  /** `true` cuando se pinta la tasa por mil habitantes en vez del recuento. */
  readonly perThousand = input.required<boolean>();
  readonly selected = input<number | null>(null);

  readonly districtPicked = output<number>();

  readonly width = computed(() => 720);
  readonly height = computed(() => this.projection().height);

  private readonly projection = computed(() => projectionFor(boundsOf(this.features()), this.width()));

  readonly shapes = computed<Shape[]>(() => {
    const projection = this.projection();
    const classification = this.classification();
    const byDistrict = new Map(this.rows().map((row) => [row.districtId, row]));

    return this.features().map((feature) => {
      const row = byDistrict.get(feature.id);
      const value = this.valueOf(row);
      return {
        districtId: feature.id,
        name: feature.properties.shortName,
        path: pathOf(feature.geometry, projection),
        label: labelPointOf(feature.geometry, projection),
        klass: value === null ? null : classOf(value, classification),
        value,
        formatted: value === null ? 'sin denominador para el año pedido' : this.format(value),
      };
    });
  });

  readonly bins = computed(() => {
    const { breaks, min, counts } = this.classification();
    return breaks.map((upper, index) => ({
      range: `${this.format(index === 0 ? min : breaks[index - 1])} – ${this.format(upper)}`,
      count: counts[index] ?? 0,
    }));
  });

  readonly isPoor = computed(() => (this.measure().coverage.pointCoverage ?? 1) < 0.5);

  /** La frase que ADR-020 §9 obliga a enseñar, con sus dos cifras y su porcentaje. */
  readonly coverageSentence = computed(() => {
    const { total, assigned, pointCoverage } = this.measure().coverage;
    const unit = this.title().toLowerCase();
    if (total === 0) {
      return `No hay ${unit} en la ventana pedida, así que no hay nada que situar.`;
    }
    const percent = ((pointCoverage ?? 0) * 100).toFixed(1).replace('.', ',');
    return (
      `De ${this.integer(total)} ${unit} del periodo se pudieron situar en una junta ` +
      `${this.integer(assigned)}: el ${percent} %. El mapa pinta solo esas; el resto no está repartido por ` +
      `ninguna parte y no se ajusta nada por cobertura.`
    );
  });

  protected readonly classes = CLASS_COUNT;

  private valueOf(row: DistrictRow | undefined): number | null {
    if (!row) {
      return null;
    }
    const id = this.measure().id;
    if (!this.perThousand()) {
      return row.values[id] ?? 0;
    }
    // Sin padrón del año usado no hay tasa, y el hueco se ve (ADR-020 §8).
    const rate = row.perThousandInhabitants[id];
    return rate === undefined ? null : rate;
  }

  private format(value: number): string {
    return this.perThousand()
      ? value.toLocaleString('es-ES', { maximumFractionDigits: 1 })
      : this.integer(value);
  }

  private integer(value: number): string {
    return value.toLocaleString('es-ES', { maximumFractionDigits: 0 });
  }
}
