import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { Status } from '../core/state';

/**
 * Lo que se enseña mientras un bloque no está: que se está leyendo, o que no se pudo.
 *
 * Son dos cosas distintas y antes eran la misma frase. «Leyendo la serie…» se quedaba puesto para siempre si
 * la petición fallaba, y el vacío de una tabla decía «ningún registro casa con estos filtros» cuando en
 * realidad no se había podido preguntar. Aquí el fallo se dice, con el motivo que da la API y con un botón
 * para volver a intentarlo: no hay reintento automático, porque un error se enseña y no se disimula
 * (ADR-022 §5).
 */
@Component({
  selector: 'obs-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './state.css',
  template: `
    @if (status() === 'loading') {
      <p class="loading" role="status">Leyendo {{ what() }}…</p>
    } @else if (status() === 'failed') {
      <div class="failed" role="alert">
        <p>
          <strong>No se ha podido leer {{ what() }}.</strong>
          @if (error()) {
            {{ error() }}
          }
        </p>
        <button type="button" (click)="retry.emit()">Volver a intentarlo</button>
      </div>
    }
  `,
})
export class State {
  readonly status = input.required<Status>();
  readonly error = input<string | null>(null);
  /** Qué se estaba leyendo, con artículo: «la serie por mes», «las partidas». */
  readonly what = input.required<string>();

  readonly retry = output<void>();
}
