import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

export interface Series {
  name: string;
  points: { key: string; label: string; value: number }[];
}

/**
 * Líneas para series largas (165 meses de quejas) y para etapas ordenadas (los cuatro importes del ciclo
 * presupuestario a lo largo de 20 ejercicios).
 *
 * Cumple lo que pide la guía: trazo de 2 px, **una sola escala** —nunca dos ejes—, rejilla recesiva, retícula
 * de hover con línea vertical y tooltip de todas las series a la vez, leyenda siempre presente con dos o más
 * series y **etiqueta directa al final de cada línea** cuando son cuatro o menos, para que la identidad no
 * dependa solo del color.
 */
@Component({
  selector: 'obs-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './chart.css',
  template: `
    <figure class="chart">
      @if (series().length > 1) {
        <ul class="legend">
          @for (one of series(); track one.name) {
            <li><span class="key" [class]="'s' + $index"></span>{{ one.name }}</li>
          }
        </ul>
      }

      <svg
        [attr.viewBox]="'0 0 ' + W + ' ' + H"
        preserveAspectRatio="none"
        role="img"
        [attr.aria-label]="title()"
        (pointermove)="track($event)"
        (pointerleave)="index.set(null)"
      >
        @for (line of gridLines(); track line.value) {
          <g class="grid">
            <line [attr.x1]="PAD_L" [attr.x2]="W - PAD_R" [attr.y1]="line.y" [attr.y2]="line.y" />
            <text [attr.x]="PAD_L - 6" [attr.y]="line.y + 3">{{ line.text }}</text>
          </g>
        }

        @if (index() !== null) {
          <line class="crosshair" [attr.x1]="crosshairX()" [attr.x2]="crosshairX()" [attr.y1]="PAD_T" [attr.y2]="baseline" />
        }

        @for (path of paths(); track path.name) {
          <path class="line" [class]="'line s' + $index" [attr.d]="path.d" />
          @if (index() !== null) {
            <circle class="dot" [class]="'dot s' + $index" [attr.cx]="crosshairX()" [attr.cy]="path.dotY" r="4" />
          }
        }

        <line class="axis" [attr.x1]="PAD_L" [attr.x2]="W - PAD_R" [attr.y1]="baseline" [attr.y2]="baseline" />

        @for (tick of ticks(); track tick.key) {
          <text class="tick" [attr.x]="tick.x" [attr.y]="H - 6">{{ tick.text }}</text>
        }
      </svg>

      @if (reading(); as read) {
        <figcaption class="tooltip" role="status">
          <strong>{{ read.label }}</strong>
          @for (value of read.values; track value.name) {
            <span class="v"><span class="key" [class]="'s' + $index"></span>{{ value.text }}</span>
          }
        </figcaption>
      } @else {
        <figcaption class="hint">{{ hint() }}</figcaption>
      }
    </figure>
  `,
})
export class LineChart {
  readonly series = input.required<Series[]>();
  readonly title = input.required<string>();
  readonly format = input.required<(value: number) => string>();
  readonly hint = input('Pasa el ratón por el gráfico para leer cualquier punto de la serie.');

  protected readonly W = 760;
  protected readonly H = 240;
  protected readonly PAD_L = 64;
  protected readonly PAD_R = 8;
  protected readonly PAD_T = 12;
  protected readonly PAD_B = 24;
  protected readonly plotH = this.H - this.PAD_T - this.PAD_B;
  protected readonly baseline = this.H - this.PAD_B;

  protected readonly index = signal<number | null>(null);

  private readonly length = computed(() => Math.max(1, this.series()[0]?.points.length ?? 1));

  private readonly max = computed(() =>
    niceCeilingOf(Math.max(1, ...this.series().flatMap((one) => one.points.map((p) => p.value)))),
  );

  private x(index: number): number {
    const span = this.W - this.PAD_L - this.PAD_R;
    return this.PAD_L + (this.length() === 1 ? span / 2 : (index / (this.length() - 1)) * span);
  }

  private y(value: number): number {
    return this.baseline - (value / this.max()) * this.plotH;
  }

  readonly crosshairX = computed(() => this.x(this.index() ?? 0));

  readonly paths = computed(() =>
    this.series().map((one) => ({
      name: one.name,
      d: one.points.map((point, i) => `${i === 0 ? 'M' : 'L'}${this.x(i)} ${this.y(point.value)}`).join(''),
      dotY: this.y(one.points[this.index() ?? 0]?.value ?? 0),
    })),
  );

  readonly gridLines = computed(() => {
    const max = this.max();
    return [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
      value: max * fraction,
      y: this.baseline - fraction * this.plotH,
      text: fraction === 0 ? '' : this.format()(max * fraction),
    }));
  });

  readonly ticks = computed(() => {
    const points = this.series()[0]?.points ?? [];
    const every = Math.ceil(points.length / 9);
    return points
      .filter((_, i) => i % every === 0 || i === points.length - 1)
      .map((point, i, kept) => ({
        key: point.key,
        x: this.x(points.indexOf(point)),
        text: kept.length > 1 ? point.label : point.label,
      }));
  });

  readonly reading = computed(() => {
    const at = this.index();
    if (at === null) {
      return null;
    }
    const first = this.series()[0]?.points[at];
    if (!first) {
      return null;
    }
    return {
      label: first.label,
      values: this.series().map((one) => ({
        name: one.name,
        text: `${one.name}: ${this.format()(one.points[at]?.value ?? 0)}`,
      })),
    };
  });

  protected track(event: PointerEvent): void {
    const target = event.currentTarget as SVGSVGElement;
    const box = target.getBoundingClientRect();
    const ratio = (event.clientX - box.left) / box.width;
    const span = this.W - this.PAD_L - this.PAD_R;
    const position = (ratio * this.W - this.PAD_L) / span;
    const at = Math.round(position * (this.length() - 1));
    this.index.set(Math.min(this.length() - 1, Math.max(0, at)));
  }
}

function niceCeilingOf(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalised = value / magnitude;
  const step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 2.5 ? 2.5 : normalised <= 5 ? 5 : 10;
  return step * magnitude;
}
