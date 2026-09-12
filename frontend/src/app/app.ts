import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/**
 * El armazón: cabecera con el nombre del producto, navegación de secciones y colofón del sitio.
 *
 * La cabecera dice lo que la herramienta es en una línea, porque un observatorio que no declara su postura
 * editorial la está escondiendo (SPEC.md §1).
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly sections = [
    { route: '/presupuesto', label: 'Presupuesto' },
    { route: '/contratacion', label: 'Contratación' },
    { route: '/subvenciones', label: 'Subvenciones' },
    { route: '/quejas', label: 'Quejas' },
    { route: '/actividad', label: 'Actividad urbana' },
    { route: '/territorio', label: 'Territorio' },
    { route: '/catalogo', label: 'Catálogo' },
  ];
}
