import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

interface Section {
  route: string;
  label: string;
  /** Trazado SVG de 24×24, dibujado a trazo. Los iconos van en el bundle, no en una fuente remota. */
  icon: string;
}

interface Group {
  label: string;
  /** Color de la familia: identifica el área de un vistazo en la barra y en la cabecera de página. */
  accent: string;
  sections: Section[];
}

/**
 * El armazón de la aplicación: barra lateral fija en escritorio, cajón desplegable en móvil.
 *
 * Las siete secciones van **agrupadas por familia** porque tres de ellas son dinero y verlas sueltas obligaba a
 * leerlas una por una. Cada familia tiene su color, que se repite en la cabecera de su página.
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  readonly menuOpen = signal(false);

  protected readonly groups: Group[] = [
    {
      label: 'Dinero',
      accent: 'var(--cat-2)',
      sections: [
        {
          route: '/presupuesto',
          label: 'Presupuesto',
          icon: 'M3 20h18M6 16V9m5 7V5m5 11v-4',
        },
        {
          route: '/contratacion',
          label: 'Contratación',
          icon: 'M9 4h6l1 3H8l1-3ZM4 7h16v13H4V7Zm4 5h8m-8 4h5',
        },
        {
          route: '/subvenciones',
          label: 'Subvenciones',
          icon: 'M12 3v18M8 7h6a2.5 2.5 0 0 1 0 5H9a2.5 2.5 0 0 0 0 5h7',
        },
      ],
    },
    {
      label: 'Ciudad',
      accent: 'var(--cat-1)',
      sections: [
        {
          route: '/quejas',
          label: 'Quejas',
          icon: 'M4 5h16v11H9l-5 4V5Zm4 4h8m-8 3h5',
        },
        {
          route: '/actividad',
          label: 'Actividad urbana',
          icon: 'M4 20V9l5-4 5 4v11M14 20V12h6v8M4 20h16M7 12h2m-2 4h2',
        },
        {
          route: '/territorio',
          label: 'Territorio',
          icon: 'M9 4 3 6.5v13L9 17l6 2.5 6-2.5v-13L15 6.5 9 4Zm0 0v13m6-10.5v13',
        },
      ],
    },
    {
      label: 'Calidad del dato',
      accent: 'var(--cat-3)',
      sections: [
        {
          route: '/catalogo',
          label: 'Catálogo',
          icon: 'M5 4h14v16H5V4Zm3 4h8m-8 4h8m-8 4h5',
        },
      ],
    },
  ];

  constructor(router: Router) {
    // Al navegar se cierra el cajón: en móvil quedaría tapando la pantalla que acabas de pedir.
    router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        this.menuOpen.set(false);
      }
    });
  }
}
