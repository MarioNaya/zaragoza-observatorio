import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Observatory } from '../core/api';
import { euroShort, integer, percent, year } from '../core/format';
import { Loaded, Status } from '../core/state';

interface Card {
  route: string;
  /** Color de la familia, el mismo que la barra lateral y la cabecera de su página. */
  accent: string;
  eyebrow: string;
  title: string;
  blurb: string;
  figures: { value: string; label: string }[];
  /**
   * En qué estado está el resumen del que salen las cifras de esta tarjeta.
   *
   * Hace falta porque antes los seis errores se tragaban con un `error: () => undefined` y la tarjeta pintaba
   * un guion: desde fuera, «no hay dato» y «no se pudo leer» eran lo mismo. Son cosas distintas y esta es la
   * pantalla de entrada (ADR-022 §5).
   */
  status: Status;
  reload: () => void;
}

/**
 * La portada. No es un cuadro de mandos: es un sumario, que es lo que corresponde a un producto editorial.
 * Cada bloque dice **qué hay dentro y qué no se puede hacer con ello**, porque las dos cosas son igual de
 * útiles para quien va a analizar.
 */
@Component({
  selector: 'obs-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './home.html',
})
export class HomePage {
  private readonly api = inject(Observatory);

  private readonly budget = new Loaded(() => this.api.budgetSummary(), 'el resumen del presupuesto');
  private readonly contracts = new Loaded(() => this.api.spendingSummary(), 'el de contratación');
  private readonly grants = new Loaded(() => this.api.grantsSummary(), 'el de subvenciones');
  private readonly citizen = new Loaded(() => this.api.citizenSummary(), 'el de quejas');
  private readonly urban = new Loaded(() => this.api.urbanSummary(), 'el de actividad urbana');
  private readonly catalog = new Loaded(() => this.api.catalogSummary(), 'el del catálogo');

  readonly cards = computed<Card[]>(() => {
    const budget = this.budget.value();
    const contracts = this.contracts.value();
    const grants = this.grants.value();
    const citizen = this.citizen.value();
    const urban = this.urban.value();
    const catalog = this.catalog.value();
    return [
      {
        route: '/presupuesto',
        accent: 'var(--cat-2)',
        eyebrow: 'Dinero · 01',
        title: 'El presupuesto de gastos',
        blurb:
          'Lo único del observatorio que dice lo que se pagó de verdad. Veinte ejercicios en instantáneas datadas, con las cuatro cifras del ciclo por separado.',
        figures: [
          { value: budget ? euroShort(budget.latestAmounts.obligations) : '—', label: 'ejecutado en la última foto' },
          {
            // Las dos fechas son del censo y pueden no estar: con la base recién creada no hay serie.
            value:
              budget && year(budget.firstSnapshot) && year(budget.lastSnapshot)
                ? `${year(budget.firstSnapshot)}–${year(budget.lastSnapshot)}`
                : '—',
            label: 'ejercicios',
          },
        ],
        status: this.budget.status(),
        reload: () => this.budget.reload(),
      },
      {
        route: '/contratacion',
        accent: 'var(--cat-2)',
        eyebrow: 'Dinero · 02',
        title: 'La contratación pública',
        blurb:
          'Lo licitado y lo adjudicado, nunca lo pagado. Incluidos los 2.271 procesos que el listado documentado de la API esconde sin decirlo.',
        figures: [
          { value: contracts ? euroShort(contracts.awardedAmount) : '—', label: 'adjudicado' },
          { value: contracts ? integer(contracts.processes) : '—', label: 'procesos' },
        ],
        status: this.contracts.status(),
        reload: () => this.contracts.reload(),
      },
      {
        route: '/subvenciones',
        accent: 'var(--cat-2)',
        eyebrow: 'Dinero · 03',
        title: 'Las subvenciones',
        blurb:
          'Quién recibe cuánto, con el beneficiario contado y no nombrado. El ayuntamiento publica el nombre y el DNI de miles de personas; aquí no entra ninguno.',
        figures: [
          { value: grants ? euroShort(grants.granted) : '—', label: 'concedido' },
          { value: grants ? integer(grants.beneficiaries) : '—', label: 'beneficiarios' },
        ],
        status: this.grants.status(),
        reload: () => this.grants.reload(),
      },
      {
        route: '/quejas',
        accent: 'var(--cat-1)',
        eyebrow: 'Ciudadanía · 01',
        title: 'Quejas y sugerencias',
        blurb:
          'Trece años de reclamaciones vecinales, sin su texto: el ayuntamiento lo publica sin anonimizar y este observatorio directamente no lo pide.',
        figures: [
          { value: citizen ? integer(citizen.total) : '—', label: 'quejas' },
          {
            value: citizen
              ? percent((citizen.assignment.byAssignment['RESOLVED'] ?? 0) / citizen.total)
              : '—',
            label: 'se pueden situar',
          },
        ],
        status: this.citizen.status(),
        reload: () => this.citizen.reload(),
      },
      {
        route: '/actividad',
        accent: 'var(--cat-1)',
        eyebrow: 'Ciudad · 01',
        title: 'Actividad urbana',
        blurb:
          'Locales con licencia y sus licencias, que son dos unidades distintas. Es la fuente territorial con mejor cobertura del producto.',
        figures: [
          { value: urban ? integer(urban.premises) : '—', label: 'locales' },
          { value: urban ? integer(urban.licences) : '—', label: 'licencias' },
        ],
        status: this.urban.status(),
        reload: () => this.urban.reload(),
      },
      {
        route: '/territorio',
        accent: 'var(--cat-1)',
        eyebrow: 'Territorio · 01',
        title: 'El cruce por junta',
        blurb:
          'Las tres medidas territorializables sobre las 29 juntas, con su cobertura al lado y sin dividir una por otra. El dinero no está aquí porque no tiene junta.',
        figures: [
          { value: '29', label: 'juntas' },
          { value: '3', label: 'medidas cruzables' },
        ],
        // Las dos cifras son del catálogo de medidas, no de una respuesta: no hay nada que leer ni que fallar.
        status: 'ready',
        reload: () => undefined,
      },
      {
        route: '/catalogo',
        accent: 'var(--cat-3)',
        eyebrow: 'Calidad del dato · 01',
        title: 'El monitor de frescura',
        blurb:
          'Se pregunta a diario a cada ficha del catálogo municipal y se guarda la respuesta. No mide si un dato es bueno: mide si sigue vivo.',
        figures: [
          { value: catalog ? integer(catalog.datasets) : '—', label: 'fichas vigiladas' },
          {
            value: catalog
              ? percent((catalog.byDeclaredFreshness['NOT_EVALUABLE'] ?? 0) / catalog.datasets)
              : '—',
            label: 'no evaluables',
          },
        ],
        status: this.catalog.status(),
        reload: () => this.catalog.reload(),
      },
    ];
  });
}
