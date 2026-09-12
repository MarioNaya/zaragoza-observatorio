import { ChangeDetectionStrategy, Component } from '@angular/core';

import { CrossTabScreen } from './cross-tab/cross-tab';

/**
 * El armazón. Una sola pantalla por ahora: el cruce territorial (ADR-019, ADR-020). La cabecera dice lo que la
 * herramienta es y —más importante— lo que no hace, porque es lo que la distingue de un portal de datos.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CrossTabScreen],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {}
