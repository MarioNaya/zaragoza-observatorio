import { ChangeDetectionStrategy, Component, input } from '@angular/core';

export interface Stat {
  value: string;
  label: string;
  /** La letra pequeña: el matiz que impide leer mal la cifra. Va siempre que haga falta, no como adorno. */
  note?: string;
  /** Marca la cifra que hay que leer con cuidado (cobertura baja, hueco de la fuente). */
  caution?: boolean;
}

/**
 * La fila de cifras de cabecera. La guía de visualización dice que a veces la respuesta **no es un gráfico**
 * sino un número grande: aquí es el caso, porque estas cifras describen un universo, no una serie.
 *
 * Cada una lleva su nota debajo. Es lo que separa «590 millones» de «590 millones de obligación neta, que es
 * gasto ejecutado y no pagado».
 */
@Component({
  selector: 'obs-stats',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './stats.css',
  template: `
    <dl class="stats" [class.compact]="compact()">
      @for (stat of data(); track stat.label) {
        <div [class.caution]="stat.caution">
          <dd class="num">{{ stat.value }}</dd>
          <dt>{{ stat.label }}</dt>
          @if (stat.note) {
            <p>{{ stat.note }}</p>
          }
        </div>
      }
    </dl>
  `,
})
export class Stats {
  readonly data = input.required<Stat[]>();
  readonly compact = input(false);
}
