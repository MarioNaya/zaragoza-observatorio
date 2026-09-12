import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CrossTabQuery, Observatory } from '../api/observatory';
import {
  ALL_MEASURES,
  CrossTab,
  Denominator,
  DistrictBoundaries,
  DistrictCard,
  MEASURE_LABELS,
  MeasureColumn,
  MeasureId,
} from '../api/types';
import { Choropleth } from '../map/choropleth';
import {
  CLASSIFICATION_HINTS,
  CLASSIFICATION_LABELS,
  ClassificationMethod,
  classify,
} from '../map/classification';
import { DistrictCardView } from './district-card';
import { Matrix } from './matrix';

/**
 * La pantalla del cruce territorial (ADR-020).
 *
 * Su estado es exactamente el de la consulta, y eso es deliberado: todo lo que cambia lo que se ve es un
 * parámetro que la API entiende y que está escrito en la pantalla. No hay ningún ajuste que ocurra aquí y no
 * pueda pedirse allí, salvo los dos que son de dibujo y solo de dibujo —el método de clasificación y qué
 * columna pinta el mapa—, que es la frontera que marca la regla 8.
 */
@Component({
  selector: 'obs-cross-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Choropleth, Matrix, DistrictCardView],
  styleUrl: './cross-tab.css',
  templateUrl: './cross-tab.html',
})
export class CrossTabScreen {
  private readonly api = inject(Observatory);

  // --- estado de la consulta ---------------------------------------------------------------------------

  /** Al entrar salen las tres: no es elegir un cruce, es no elegir ninguno (ADR-020 §5). */
  readonly measures = signal<MeasureId[]>([...ALL_MEASURES]);
  readonly from = signal<string | null>(null);
  readonly to = signal<string | null>(null);
  readonly denominator = signal<Denominator>('population');
  readonly populationYear = signal<number | null>(null);
  readonly sort = signal('district,asc');

  // --- estado de dibujo, que no viaja a la API ----------------------------------------------------------

  readonly method = signal<ClassificationMethod>('quantiles');
  readonly painted = signal<MeasureId | null>(null);
  readonly selected = signal<number | null>(null);

  // --- lo que llega -------------------------------------------------------------------------------------

  readonly tab = signal<CrossTab | null>(null);
  readonly caveats = signal<string[]>([]);
  readonly boundaries = signal<DistrictBoundaries | null>(null);
  readonly card = signal<DistrictCard | null>(null);
  readonly cardCaveats = signal<string[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  protected readonly allMeasures = ALL_MEASURES;
  protected readonly measureLabels = MEASURE_LABELS;
  protected readonly methods = Object.keys(CLASSIFICATION_LABELS) as ClassificationMethod[];
  protected readonly methodLabels = CLASSIFICATION_LABELS;
  protected readonly methodHints = CLASSIFICATION_HINTS;

  constructor() {
    this.api.boundaries().subscribe({
      next: (boundaries) => this.boundaries.set(boundaries),
      error: () => this.error.set('No se han podido leer los contornos de las juntas.'),
    });
    this.reload();
  }

  // --- derivados ------------------------------------------------------------------------------------------

  /**
   * La columna que pinta el mapa. Por defecto **la mejor cubierta**, que es una regla y no una preferencia:
   * abrir por la peor cubierta sería enseñar primero la columna más frágil, y elegirla a mano sería la voz del
   * producto (ADR-020 §5).
   */
  readonly paintedMeasure = computed<MeasureColumn | null>(() => {
    const tab = this.tab();
    if (!tab || tab.measures.length === 0) {
      return null;
    }
    const chosen = this.painted();
    return (
      tab.measures.find((measure) => measure.id === chosen) ??
      [...tab.measures].sort(
        (a, b) => (b.coverage.pointCoverage ?? 0) - (a.coverage.pointCoverage ?? 0),
      )[0]
    );
  });

  readonly paintedByRule = computed(() => this.painted() === null);

  /** Se pintan tasas solo si se pidió denominador; si no, recuentos. */
  readonly perThousand = computed(() => this.denominator() === 'population');

  readonly classification = computed(() => {
    const tab = this.tab();
    const measure = this.paintedMeasure();
    if (!tab || !measure) {
      return classify([], this.method());
    }
    const values = tab.items
      .map((row) =>
        this.perThousand() ? row.perThousandInhabitants[measure.id] : row.values[measure.id],
      )
      .filter((value): value is number => value !== undefined);
    return classify(values, this.method());
  });

  readonly paintedTitle = computed(() => {
    const measure = this.paintedMeasure();
    if (!measure) {
      return '';
    }
    const label = MEASURE_LABELS[measure.id] ?? measure.id;
    return this.perThousand() ? `${label} por mil habitantes` : label;
  });

  /** Los años de padrón que la respuesta ha usado de verdad, para poder ofrecerlos sin inventarlos. */
  readonly populationYears = computed(() => {
    const tab = this.tab();
    if (!tab) {
      return [];
    }
    return [...new Set(tab.items.map((row) => row.populationYear).filter((year): year is number => !!year))]
      .sort((a, b) => b - a);
  });

  readonly withoutDenominator = computed(() => {
    const tab = this.tab();
    if (!tab || this.denominator() === 'none') {
      return 0;
    }
    return tab.items.filter((row) => row.population === null).length;
  });

  // --- acciones -------------------------------------------------------------------------------------------

  toggleMeasure(id: MeasureId): void {
    const current = this.measures();
    // Se conserva el orden del catálogo para que las columnas no bailen al añadir y quitar.
    const next = ALL_MEASURES.filter((measure) =>
      measure === id ? !current.includes(id) : current.includes(measure),
    );
    // El catálogo es cerrado y la matriz necesita al menos una columna: quitar la última no se permite.
    if (next.length === 0) {
      return;
    }
    this.measures.set(next);
    if (!next.includes(this.sort().split(',')[0] as MeasureId) && this.sort().split(',')[0] !== 'district') {
      this.sort.set('district,asc');
    }
    this.reload();
  }

  /** Ordenar es volver a pedir, nunca reordenar el array que ya está en memoria (ADR-020 §12). */
  sortByField(field: string): void {
    const [current, direction] = this.sort().split(',');
    if (current === field) {
      this.sort.set(`${field},${direction === 'desc' ? 'asc' : 'desc'}`);
    } else {
      // Una medida se mira de mayor a menor; la junta, alfabéticamente.
      this.sort.set(`${field},${field === 'district' ? 'asc' : 'desc'}`);
    }
    this.reload();
  }

  setDenominator(denominator: Denominator): void {
    this.denominator.set(denominator);
    this.reload();
  }

  setPopulationYear(value: string): void {
    this.populationYear.set(value ? Number(value) : null);
    this.reload();
  }

  setWindow(which: 'from' | 'to', value: string): void {
    // El control es una fecha y la API pide un instante: se manda el comienzo de ese día en UTC.
    const instant = value ? `${value}T00:00:00Z` : null;
    (which === 'from' ? this.from : this.to).set(instant);
    this.reload();
  }

  clearWindow(): void {
    this.from.set(null);
    this.to.set(null);
    this.reload();
  }

  pickDistrict(districtId: number): void {
    if (this.selected() === districtId) {
      this.closeCard();
      return;
    }
    this.selected.set(districtId);
    this.loadCard(districtId);
  }

  private loadCard(districtId: number): void {
    this.api.district(districtId, this.query()).subscribe({
      next: (response) => {
        this.card.set(response.item);
        this.cardCaveats.set(response.caveats);
      },
      error: () => this.error.set('No se ha podido leer la ficha de la junta.'),
    });
  }

  closeCard(): void {
    this.selected.set(null);
    this.card.set(null);
  }

  protected dateValue(instant: string | null): string {
    return instant ? instant.slice(0, 10) : '';
  }

  private query(): CrossTabQuery {
    return {
      measures: this.measures(),
      from: this.from(),
      to: this.to(),
      denominator: this.denominator(),
      populationYear: this.populationYear(),
      sort: this.sort(),
    };
  }

  private reload(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.crossTab(this.query()).subscribe({
      next: (response) => {
        this.tab.set(response.item);
        this.caveats.set(response.caveats);
        this.loading.set(false);
        // La ficha abierta se rehace con la misma consulta: si cambia la ventana, cambia la ficha.
        const open = this.selected();
        if (open !== null) {
          this.loadCard(open);
        }
      },
      error: (failure) => {
        this.loading.set(false);
        // El detalle del problema viene de la API como problem+json: se enseña, no se disimula.
        this.error.set(failure?.error?.detail ?? 'No se ha podido leer el cruce territorial.');
      },
    });
  }
}
