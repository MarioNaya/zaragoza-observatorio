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
        <!--
          Dentro del div de una dl solo caben dt y dd, y el dt va antes. La nota estaba en un párrafo suelto y
          el valor iba primero, así que esto no era una lista de definiciones para un lector de pantalla (lo
          midió axe, ADR-022 §6). El orden visual —cifra, etiqueta, nota— lo pone el CSS; el del documento es
          el que se lee en voz alta. Un término con dos descripciones es válido.
        -->
        <div [class.caution]="stat.caution">
          <dt>{{ stat.label }}</dt>
          <dd class="num">{{ stat.value }}</dd>
          @if (stat.note) {
            <dd class="note">{{ stat.note }}</dd>
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
