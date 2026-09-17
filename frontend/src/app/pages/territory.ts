import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { map } from 'rxjs';

import { Observatory } from '../core/api';
import { decimal, integer, percent, toDay, toInstant } from '../core/format';
import { Loaded } from '../core/state';
import {
  ALL_MEASURES,
  CrossTabQuery,
  Denominator,
  MEASURE_LABELS,
  MeasureColumn,
  MeasureId,
} from '../core/types';
import { Choropleth } from '../map/choropleth';
import {
  CLASSIFICATION_HINTS,
  CLASSIFICATION_LABELS,
  ClassificationMethod,
  classify,
} from '../map/classification';
import { Colophon } from '../ui/colophon';
import { State } from '../ui/state';
import { DistrictCardView } from './district-card';
import { Matrix } from './matrix';

/**
 * El cruce territorial (ADR-019, ADR-020). Las decisiones editoriales de esta pantalla siguen siendo las de
 * ADR-020 y no se han tocado en el rediseño: al entrar salen las tres medidas, el mapa entra por la mejor
 * cubierta y lo dice, la clasificación se nombra con sus cortes a la vista, la paleta es secuencial, la
 * cobertura va en la leyenda y **no se divide una medida por otra**.
 */
@Component({
  selector: 'obs-territory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Choropleth, Matrix, DistrictCardView, Colophon, State],
  templateUrl: './territory.html',
})
export class TerritoryPage {
  private readonly api = inject(Observatory);

  readonly measures = signal<MeasureId[]>([...ALL_MEASURES]);
  readonly from = signal<string | null>(null);
  readonly to = signal<string | null>(null);
  readonly denominator = signal<Denominator>('population');
  readonly populationYear = signal<number | null>(null);
  readonly sort = signal('district,asc');

  readonly method = signal<ClassificationMethod>('quantiles');
  readonly painted = signal<MeasureId | null>(null);
  readonly selected = signal<number | null>(null);

  readonly tab = new Loaded(() => this.api.crossTab(this.query()), 'el cruce territorial');
  /** Los contornos se piden una sola vez por sesión: 373 KB que no cambian entre ingestas. */
  readonly boundaries = new Loaded(
    () => this.api.boundaries().pipe(map((item) => ({ caveats: [], item }))),
    'los contornos de las 29 juntas',
  );
  readonly card = new Loaded(
    () => this.api.district(this.selected() ?? 0, this.query()),
    'la ficha de la junta',
    false,
  );

  protected readonly allMeasures = ALL_MEASURES;
  protected readonly measureLabels = MEASURE_LABELS;
  protected readonly methods = Object.keys(CLASSIFICATION_LABELS) as ClassificationMethod[];
  protected readonly methodLabels = CLASSIFICATION_LABELS;
  protected readonly methodHints = CLASSIFICATION_HINTS;
  protected readonly integer = integer;
  protected readonly percent = percent;
  protected readonly toDay = toDay;

  /** La columna que pinta el mapa: por defecto **la mejor cubierta**, que es una regla y no una preferencia. */
  readonly paintedMeasure = computed<MeasureColumn | null>(() => {
    const tab = this.tab.value();
    if (!tab || tab.measures.length === 0) {
      return null;
    }
    const chosen = this.painted();
    return (
      tab.measures.find((measure) => measure.id === chosen) ??
      [...tab.measures].sort((a, b) => (b.coverage.pointCoverage ?? 0) - (a.coverage.pointCoverage ?? 0))[0]
    );
  });

  readonly paintedByRule = computed(() => this.painted() === null);
  readonly perThousand = computed(() => this.denominator() === 'population');

  readonly classification = computed(() => {
    const tab = this.tab.value();
    const measure = this.paintedMeasure();
    if (!tab || !measure) {
      return classify([], this.method());
    }
    const values = tab.items
      .map((row) => (this.perThousand() ? row.perThousandInhabitants[measure.id] : row.values[measure.id]))
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

  readonly populationYears = computed(() => {
    const tab = this.tab.value();
    if (!tab) {
      return [];
    }
    return [...new Set(tab.items.map((row) => row.populationYear).filter((year): year is number => !!year))].sort(
      (a, b) => b - a,
    );
  });

  readonly withoutDenominator = computed(() => {
    const tab = this.tab.value();
    if (!tab || this.denominator() === 'none') {
      return 0;
    }
    return tab.items.filter((row) => row.population === null).length;
  });

  /** La ventana que se está mirando, escrita. Sin esto la pantalla no dice de qué periodo son las cifras. */
  readonly windowText = computed(() => {
    const from = this.from();
    const to = this.to();
    if (!from && !to) {
      return 'todo el histórico de cada fuente';
    }
    if (from && to) {
      return `del ${toDay(from)} al ${toDay(to)}`;
    }
    return from ? `desde el ${toDay(from)}` : `hasta el ${toDay(to)}`;
  });

  toggleMeasure(id: MeasureId): void {
    const current = this.measures();
    const next = ALL_MEASURES.filter((measure) =>
      measure === id ? !current.includes(id) : current.includes(measure),
    );
    if (next.length === 0) {
      return;
    }
    this.measures.set(next);
    const field = this.sort().split(',')[0];
    if (field !== 'district' && !next.includes(field as MeasureId)) {
      this.sort.set('district,asc');
    }
    this.reload();
  }

  sortByField(field: string): void {
    const [current, direction] = this.sort().split(',');
    if (current === field) {
      this.sort.set(`${field},${direction === 'desc' ? 'asc' : 'desc'}`);
    } else {
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
    (which === 'from' ? this.from : this.to).set(toInstant(value || null));
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
    this.card.reload();
  }

  closeCard(): void {
    this.selected.set(null);
    this.card.value.set(null);
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

  /** Cada cambio de la consulta vuelve a pedir: lo que se ve es exactamente lo que se pidió. */
  private reload(): void {
    this.tab.reload();
    if (this.selected() !== null) {
      this.card.reload();
    }
  }
}
