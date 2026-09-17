import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface RankRow {
  key: string;
  label: string;
  value: number;
  /** Cifra secundaria que acompaña, ya formateada (por ejemplo «4.152 beneficiarios»). */
  note?: string;
}

/**
 * Ranking horizontal: lo que la guía pide para comparar magnitudes entre muchas categorías con nombre largo
 * —proveedores, beneficiarios, categorías de queja, epígrafes IAE—. En horizontal el nombre se lee; en
 * vertical habría que girarlo.
 *
 * Una sola serie, así que **sin leyenda**: el título la nombra. La barra es la marca y el valor va como
 * etiqueta directa al final, no dentro de la barra, para que se lea igual con la barra corta.
 */
@Component({
  selector: 'obs-ranking',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './chart.css',
  template: `
    <ol class="ranking">
      @for (row of rows(); track row.key) {
        <li>
          <span class="rank num">{{ $index + 1 }}</span>
          <span class="name" [title]="row.label">{{ row.label }}</span>
          <span class="track"><span class="fill" [style.width.%]="row.share"></span></span>
          <span class="value num">{{ format()(row.value) }}</span>
          @if (row.note) {
            <span class="note">{{ row.note }}</span>
          }
        </li>
      }
    </ol>
    @if (rows().length === 0) {
      <p class="empty">No hay datos para este filtro.</p>
    }
  `,
})
export class Ranking {
  readonly data = input.required<RankRow[]>();
  readonly format = input.required<(value: number) => string>();

  readonly rows = computed(() => {
    const data = this.data();
    const max = Math.max(1, ...data.map((row) => Math.abs(row.value)));
    return data.map((row) => ({ ...row, share: (Math.abs(row.value) / max) * 100 }));
  });
}
