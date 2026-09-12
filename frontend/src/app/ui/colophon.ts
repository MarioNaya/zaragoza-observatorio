import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { dateTime } from '../core/format';
import { Source } from '../core/types';

/**
 * El colofón: de dónde sale el dato, cuándo se leyó y con qué advertencias. Va al pie de **todas** las vistas.
 *
 * Los `caveats` se pintan **tal como llegan y todos** (ADR-020 §10). La pantalla no tiene ninguno suyo, no
 * reescribe ninguno y no esconde ninguno detrás de un icono de ayuda: la voz del producto ya está escrita y
 * revisada en el backend, y aquí solo se imprime.
 *
 * La distinción que el texto repite y que importa: `ingestedAt` es **cuándo leímos el origen**, no cuándo
 * cambió el origen. Lo segundo lo mide el eje observado del catálogo (ADR-019 §10).
 */
@Component({
  selector: 'obs-colophon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './colophon.css',
  template: `
    <aside class="colophon">
      <div class="provenance">
        <h2>Procedencia</h2>
        @if (source(); as src) {
          <p>
            Conjunto de origen <code>{{ src.dataset }}</code>.
            <a [href]="src.url" target="_blank" rel="noreferrer noopener">Ver la fuente municipal</a>
          </p>
        }
        @if (ingestedAt()) {
          <p>
            Leído del origen el <time>{{ read() }}</time>. Eso es cuándo lo miramos nosotros,
            <strong>no cuándo cambió el ayuntamiento</strong>.
          </p>
        }
        @if (extra()) {
          <p>{{ extra() }}</p>
        }
      </div>

      @if (caveats().length > 0) {
        <div class="caveats">
          <h2>Advertencias de esta respuesta <span class="count num">{{ caveats().length }}</span></h2>
          <ol>
            @for (caveat of caveats(); track $index) {
              <li>{{ caveat }}</li>
            }
          </ol>
        </div>
      }
    </aside>
  `,
})
export class Colophon {
  readonly source = input<Source | null | undefined>(null);
  readonly ingestedAt = input<string | null | undefined>(null);
  readonly caveats = input.required<string[]>();
  /** Una línea más de contexto propio de la sección, cuando la procedencia sola no basta. */
  readonly extra = input<string | null>(null);

  protected read(): string {
    return dateTime(this.ingestedAt());
  }
}
