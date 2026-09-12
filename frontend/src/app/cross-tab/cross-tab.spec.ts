import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { CrossTabQuery, Observatory } from '../api/observatory';
import { ApiItem, CrossTab, DistrictBoundaries, DistrictCard, MeasureColumn } from '../api/types';
import { CrossTabScreen } from './cross-tab';

/**
 * Las invariantes de ADR-020 sobre la pantalla, con la API doblada.
 *
 * No se comprueba que «se vea bien»: se comprueba que las decisiones que la ADR toma no se puedan perder sin
 * que algo se ponga rojo. La cobertura escrita, los caveats completos, el orden pedido al backend y la ausencia
 * de cualquier cociente entre columnas.
 */
describe('CrossTabScreen', () => {
  const CAVEATS = [
    'Las filas son las 29 juntas municipales y vecinales.',
    'La suma de las 29 filas de una columna no es el total de esa medida.',
    'Nada está ajustado por cobertura.',
  ];

  function measure(id: string, unit: string, assigned: number, total: number): MeasureColumn {
    return {
      id: id as MeasureColumn['id'],
      module: id.split('.')[0],
      unit,
      dateField: 'created_at',
      dateFieldMeaning: 'alta del local',
      ingestedAt: '2026-09-11T12:06:03Z',
      coverage: {
        total,
        withPoint: assigned,
        assigned,
        unassigned: total - assigned,
        pointCoverage: assigned / total,
      },
    };
  }

  // Las cifras son las reales de producción (ADR-020): la peor cubierta es citizen, con el 29,2 %.
  const MEASURES = [
    measure('citizen.requests', 'requests', 26105, 89515),
    measure('urban.premises', 'premises', 37829, 42344),
    measure('urban.licences', 'licences', 63093, 69633),
  ];

  function tabWith(sort = 'district,asc'): CrossTab {
    return {
      axis: 'district',
      window: { from: null, to: null },
      denominator: 'population',
      populationYear: null,
      sort,
      measures: MEASURES,
      districts: 2,
      items: [
        {
          districtId: 1,
          name: 'Junta Municipal Actur-Rey Fernando',
          shortName: 'Actur-Rey Fernando',
          padronId: 17,
          population: 55709,
          populationYear: 2024,
          values: { 'citizen.requests': 1750, 'urban.premises': 2708, 'urban.licences': 4429 },
          perThousandInhabitants: {
            'citizen.requests': 31.4,
            'urban.premises': 48.6,
            'urban.licences': 79.5,
          },
        },
        {
          districtId: 2,
          name: 'Junta Municipal La Almozara',
          shortName: 'La Almozara',
          padronId: 2,
          // Sin padrón del año pedido: el hueco tiene que verse (ADR-020 §8).
          population: null,
          populationYear: null,
          values: { 'citizen.requests': 900, 'urban.premises': 1200, 'urban.licences': 1900 },
          perThousandInhabitants: {},
        },
      ],
    };
  }

  const BOUNDARIES: DistrictBoundaries = {
    type: 'FeatureCollection',
    count: 2,
    features: [1, 2].map((id) => ({
      type: 'Feature' as const,
      id,
      geometry: {
        type: 'Polygon' as const,
        coordinates: [
          [
            [-0.9 - id * 0.01, 41.6],
            [-0.8 - id * 0.01, 41.6],
            [-0.8 - id * 0.01, 41.7],
            [-0.9 - id * 0.01, 41.6],
          ],
        ],
      },
      properties: {
        id,
        name: `Junta ${id}`,
        shortName: id === 1 ? 'Actur-Rey Fernando' : 'La Almozara',
        kind: 'MUNICIPAL',
        padronId: id,
      },
    })),
  };

  let asked: CrossTabQuery[] = [];

  function render() {
    asked = [];
    TestBed.configureTestingModule({
      imports: [CrossTabScreen],
      providers: [
        {
          provide: Observatory,
          useValue: {
            crossTab: (query: CrossTabQuery) => {
              asked.push(query);
              return of<ApiItem<CrossTab>>({ caveats: CAVEATS, item: tabWith(query.sort) });
            },
            district: () =>
              of<ApiItem<DistrictCard>>({
                caveats: CAVEATS,
                item: {
                  window: { from: null, to: null },
                  denominator: 'population',
                  populationYear: null,
                  measures: MEASURES,
                  item: tabWith().items[0],
                  populationSeries: [
                    { year: 2020, population: 54000 },
                    { year: 2024, population: 55709 },
                  ],
                },
              }),
            boundaries: () => of(BOUNDARIES),
          },
        },
      ],
    });
    return TestBed.createComponent(CrossTabScreen);
  }

  it('al entrar pide las tres medidas: no elegir ninguna, no elegir un cruce', () => {
    render();

    expect(asked[0].measures).toEqual(['citizen.requests', 'urban.premises', 'urban.licences']);
    expect(asked[0].sort).toBe('district,asc');
  });

  it('el mapa entra por la medida mejor cubierta, que es una regla y no una preferencia', async () => {
    const fixture = render();
    await fixture.whenStable();

    expect(fixture.componentInstance.paintedMeasure()?.id).toBe('urban.licences');
    expect(fixture.componentInstance.paintedByRule()).toBe(true);
  });

  it('la cobertura de la columna pintada se escribe con su número', async () => {
    const fixture = render();
    fixture.componentInstance.painted.set('citizen.requests');
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('29,2 %');
    expect(text).toContain('89.515');
    expect(text).toContain('26.105');
    expect(text).toContain('no se ajusta nada por cobertura');
  });

  it('pinta todos los caveats que llegan y ninguno más', async () => {
    const fixture = render();
    await fixture.whenStable();

    const rendered = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.caveats li'),
    ).map((li) => li.textContent?.trim());
    expect(rendered).toEqual(CAVEATS);
  });

  it('ordenar es volver a pedir al backend, no reordenar lo que ya está en memoria', async () => {
    const fixture = render();
    await fixture.whenStable();

    fixture.componentInstance.sortByField('citizen.requests');

    expect(asked).toHaveLength(2);
    expect(asked[1].sort).toBe('citizen.requests,desc');
  });

  it('quitar la última medida no se permite: la matriz necesita una columna', async () => {
    const fixture = render();
    await fixture.whenStable();
    const screen = fixture.componentInstance;

    screen.toggleMeasure('citizen.requests');
    screen.toggleMeasure('urban.premises');
    screen.toggleMeasure('urban.licences');

    expect(screen.measures()).toEqual(['urban.licences']);
  });

  it('la junta sin padrón del año pedido sale sin denominador y se dice cuántas son', async () => {
    const fixture = render();
    await fixture.whenStable();

    expect(fixture.componentInstance.withoutDenominator()).toBe(1);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('sin denominador');
  });

  it('con cifras absolutas no se pintan tasas', async () => {
    const fixture = render();
    fixture.componentInstance.setDenominator('none');
    await fixture.whenStable();

    expect(fixture.componentInstance.perThousand()).toBe(false);
    expect(asked[asked.length - 1].denominator).toBe('none');
  });
});
