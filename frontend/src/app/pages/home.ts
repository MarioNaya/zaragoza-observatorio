import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Observatory } from '../core/api';
import { euroShort, integer, percent } from '../core/format';

interface Card {
  route: string;
  eyebrow: string;
  title: string;
  blurb: string;
  figures: { value: string; label: string }[];
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

  private readonly budget = signal<{ obligations: string; span: string } | null>(null);
  private readonly contracts = signal<{ awarded: string; processes: string } | null>(null);
  private readonly grants = signal<{ granted: string; beneficiaries: string } | null>(null);
  private readonly citizen = signal<{ total: string; coverage: string } | null>(null);
  private readonly urban = signal<{ premises: string; licences: string } | null>(null);
  private readonly catalog = signal<{ datasets: string; notEvaluable: string } | null>(null);

  constructor() {
    this.api.budgetSummary().subscribe({
      next: (r) =>
        this.budget.set({
          obligations: euroShort(r.item.latestAmounts.obligations),
          span: `${r.item.firstSnapshot.slice(0, 4)}–${r.item.lastSnapshot.slice(0, 4)}`,
        }),
      error: () => undefined,
    });
    this.api.spendingSummary().subscribe({
      next: (r) =>
        this.contracts.set({
          awarded: euroShort(r.item.awardedAmount),
          processes: integer(r.item.processes),
        }),
      error: () => undefined,
    });
    this.api.grantsSummary().subscribe({
      next: (r) =>
        this.grants.set({
          granted: euroShort(r.item.granted),
          beneficiaries: integer(r.item.beneficiaries),
        }),
      error: () => undefined,
    });
    this.api.citizenSummary().subscribe({
      next: (r) =>
        this.citizen.set({
          total: integer(r.item.total),
          coverage: percent((r.item.assignment.byAssignment['RESOLVED'] ?? 0) / r.item.total),
        }),
      error: () => undefined,
    });
    this.api.urbanSummary().subscribe({
      next: (r) =>
        this.urban.set({ premises: integer(r.item.premises), licences: integer(r.item.licences) }),
      error: () => undefined,
    });
    this.api.catalogSummary().subscribe({
      next: (r) =>
        this.catalog.set({
          datasets: integer(r.datasets),
          notEvaluable: percent((r.byDeclaredFreshness['NOT_EVALUABLE'] ?? 0) / r.datasets),
        }),
      error: () => undefined,
    });
  }

  readonly cards = computed<Card[]>(() => [
    {
      route: '/presupuesto',
      eyebrow: 'Dinero · 01',
      title: 'El presupuesto de gastos',
      blurb:
        'Lo único del observatorio que dice lo que se pagó de verdad. Veinte ejercicios en instantáneas datadas, con las cuatro cifras del ciclo por separado.',
      figures: [
        { value: this.budget()?.obligations ?? '—', label: 'ejecutado en la última foto' },
        { value: this.budget()?.span ?? '—', label: 'ejercicios' },
      ],
    },
    {
      route: '/contratacion',
      eyebrow: 'Dinero · 02',
      title: 'La contratación pública',
      blurb:
        'Lo licitado y lo adjudicado, nunca lo pagado. Incluidos los 2.271 procesos que el listado documentado de la API esconde sin decirlo.',
      figures: [
        { value: this.contracts()?.awarded ?? '—', label: 'adjudicado' },
        { value: this.contracts()?.processes ?? '—', label: 'procesos' },
      ],
    },
    {
      route: '/subvenciones',
      eyebrow: 'Dinero · 03',
      title: 'Las subvenciones',
      blurb:
        'Quién recibe cuánto, con el beneficiario contado y no nombrado. El ayuntamiento publica el nombre y el DNI de miles de personas; aquí no entra ninguno.',
      figures: [
        { value: this.grants()?.granted ?? '—', label: 'concedido' },
        { value: this.grants()?.beneficiaries ?? '—', label: 'beneficiarios' },
      ],
    },
    {
      route: '/quejas',
      eyebrow: 'Ciudadanía · 01',
      title: 'Quejas y sugerencias',
      blurb:
        'Trece años de reclamaciones vecinales, sin su texto: el ayuntamiento lo publica sin anonimizar y este observatorio directamente no lo pide.',
      figures: [
        { value: this.citizen()?.total ?? '—', label: 'quejas' },
        { value: this.citizen()?.coverage ?? '—', label: 'se pueden situar' },
      ],
    },
    {
      route: '/actividad',
      eyebrow: 'Ciudad · 01',
      title: 'Actividad urbana',
      blurb:
        'Locales con licencia y sus licencias, que son dos unidades distintas. Es la fuente territorial con mejor cobertura del producto.',
      figures: [
        { value: this.urban()?.premises ?? '—', label: 'locales' },
        { value: this.urban()?.licences ?? '—', label: 'licencias' },
      ],
    },
    {
      route: '/territorio',
      eyebrow: 'Territorio · 01',
      title: 'El cruce por junta',
      blurb:
        'Las tres medidas territorializables sobre las 29 juntas, con su cobertura al lado y sin dividir una por otra. El dinero no está aquí porque no tiene junta.',
      figures: [
        { value: '29', label: 'juntas' },
        { value: '3', label: 'medidas cruzables' },
      ],
    },
    {
      route: '/catalogo',
      eyebrow: 'Calidad del dato · 01',
      title: 'El monitor de frescura',
      blurb:
        'Se pregunta a diario a cada ficha del catálogo municipal y se guarda la respuesta. No mide si un dato es bueno: mide si sigue vivo.',
      figures: [
        { value: this.catalog()?.datasets ?? '—', label: 'fichas vigiladas' },
        { value: this.catalog()?.notEvaluable ?? '—', label: 'no evaluables' },
      ],
    },
  ]);
}
