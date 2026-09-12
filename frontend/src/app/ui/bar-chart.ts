import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

/** Un punto de la serie. `series` indexa la paleta categórica cuando hay más de una. */
export interface Bar {
  key: string;
  label: string;
  value: number;
  /** Segundo valor opcional para barras agrupadas (por ejemplo licitado y adjudicado). */
  value2?: number;
}

/**
 * Barras verticales para una serie temporal corta (20 ejercicios, 17 años de subvenciones).
 *
 * Reglas de la guía de visualización que este componente cumple: marcas finas con **2 px de hueco** entre
 * barras adyacentes, extremo de dato redondeado 4 px y anclado a la línea base, rejilla recesiva, **una sola
 * escala** (nunca dos ejes), etiquetas directas selectivas en vez de un número sobre cada barra, y capa de
 * hover con su tooltip. El texto va en tinta, no en el color de la serie.
 */
@Component({
  selector: 'obs-bar-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './chart.css',
  template: `
    <figure class="chart">
      @if (legend().length > 1) {
        <ul class="legend">
          @for (name of legend(); track name) {
            <li><span class="key" [class]="'s' + $index"></span>{{ name }}</li>
          }
        </ul>
      }

      <svg
        [attr.viewBox]="'0 0 ' + W + ' ' + H"
        role="img"
        [attr.aria-label]="title()"
        (pointerleave)="hover.set(null)"
      >
        <!-- Rejilla recesiva: cuatro líneas y sus valores, nada más. -->
        @for (line of gridLines(); track line.value) {
          <g class="grid">
            <line [attr.x1]="PAD_L" [attr.x2]="W - PAD_R" [attr.y1]="line.y" [attr.y2]="line.y" />
            <text [attr.x]="PAD_L - 6" [attr.y]="line.y + 3">{{ line.text }}</text>
          </g>
        }

        @for (bar of bars(); track bar.key) {
          <g
            class="bar"
            [class.dim]="hover() !== null && hover() !== bar.key"
            (pointerenter)="hover.set(bar.key)"
            (click)="picked.emit(bar.key)"
          >
            <!-- Zona sensible del ancho completo: el objetivo es mayor que la marca. -->
            <rect class="hit" [attr.x]="bar.slotX" [attr.y]="PAD_T" [attr.width]="bar.slotW" [attr.height]="plotH" />
            <rect class="mark s0" [attr.x]="bar.x" [attr.y]="bar.y" [attr.width]="bar.w" [attr.height]="bar.h" rx="2" />
            @if (bar.h2 !== null) {
              <rect
                class="mark s1"
                [attr.x]="bar.x2"
                [attr.y]="bar.y2"
                [attr.width]="bar.w"
                [attr.height]="bar.h2"
                rx="2"
              />
            }
          </g>
        }

        <line class="axis" [attr.x1]="PAD_L" [attr.x2]="W - PAD_R" [attr.y1]="baseline" [attr.y2]="baseline" />

        @for (tick of ticks(); track tick.key) {
          <text class="tick" [attr.x]="tick.x" [attr.y]="H - 6">{{ tick.text }}</text>
        }
      </svg>

      @if (hovered(); as point) {
        <figcaption class="tooltip" role="status">
          <strong>{{ point.label }}</strong>
          <span class="v"><span class="key s0"></span>{{ format()(point.value) }}</span>
          @if (point.value2 !== undefined) {
            <span class="v"><span class="key s1"></span>{{ format()(point.value2) }}</span>
          }
        </figcaption>
      } @else {
        <figcaption class="hint">{{ hint() }}</figcaption>
      }
    </figure>
  `,
})
export class BarChart {
  readonly data = input.required<Bar[]>();
  readonly title = input.required<string>();
  readonly format = input.required<(value: number) => string>();
  readonly legend = input<string[]>([]);
  readonly hint = input('Pasa el ratón por una barra para ver la cifra exacta.');

  readonly picked = output<string>();

  protected readonly W = 760;
  protected readonly H = 270;
  protected readonly PAD_L = 64;
  protected readonly PAD_R = 8;
  protected readonly PAD_T = 10;
  protected readonly PAD_B = 24;
  protected readonly plotH = this.H - this.PAD_T - this.PAD_B;
  protected readonly baseline = this.H - this.PAD_B;

  protected readonly hover = signal<string | null>(null);

  readonly hovered = computed(() => this.data().find((bar) => bar.key === this.hover()) ?? null);

  private readonly max = computed(() => {
    const values = this.data().flatMap((bar) => [bar.value, bar.value2 ?? 0]);
    return niceCeiling(Math.max(1, ...values));
  });

  readonly bars = computed(() => {
    const data = this.data();
    const max = this.max();
    const slotW = (this.W - this.PAD_L - this.PAD_R) / Math.max(1, data.length);
    const grouped = data.some((bar) => bar.value2 !== undefined);
    // 2 px de hueco entre barras adyacentes, como pide la guía; el resto es la marca.
    const markW = Math.max(2, (grouped ? slotW / 2 : slotW) - 2);

    return data.map((bar, index) => {
      const slotX = this.PAD_L + index * slotW;
      const h = (bar.value / max) * this.plotH;
      const h2 = bar.value2 === undefined ? null : (bar.value2 / max) * this.plotH;
      return {
        ...bar,
        slotX,
        slotW,
        w: markW,
        x: slotX + 1,
        y: this.baseline - h,
        h,
        x2: slotX + markW + 2,
        y2: h2 === null ? 0 : this.baseline - h2,
        h2,
      };
    });
  });

  readonly gridLines = computed(() => {
    const max = this.max();
    return [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
      value: max * fraction,
      y: this.baseline - fraction * this.plotH,
      text: fraction === 0 ? '' : this.format()(max * fraction),
    }));
  });

  /** Etiquetas selectivas: con 21 ejercicios no caben 21 rótulos, así que se ponen cada pocos. */
  readonly ticks = computed(() => {
    const bars = this.bars();
    const every = Math.ceil(bars.length / 10);
    return bars
      .filter((_, index) => index % every === 0 || index === bars.length - 1)
      .map((bar) => ({ key: bar.key, x: bar.slotX + bar.slotW / 2, text: bar.label }));
  });
}

/**
 * Un techo redondo para el eje, **cerca del dato**.
 *
 * La versión anterior solo admitía 1, 2, 2,5, 5 y 10, así que un máximo de 1,09 mM€ subía el eje a 2 mM€ y las
 * cuatro líneas del presupuesto quedaban aplastadas en la mitad inferior, superpuestas y sin poder distinguirse.
 * Con los escalones intermedios ese mismo máximo sube a 1,2 y la serie ocupa el gráfico.
 */
export function niceCeiling(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalised = value / magnitude;
  const steps = [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10];
  return (steps.find((step) => normalised <= step + 1e-9) ?? 10) * magnitude;
}
