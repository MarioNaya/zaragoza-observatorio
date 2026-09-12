import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { Observatory } from './api/observatory';
import { App } from './app';

describe('App', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        {
          provide: Observatory,
          useValue: {
            crossTab: () => of({ caveats: [], item: null }),
            district: () => of({ caveats: [], item: null }),
            boundaries: () => of({ type: 'FeatureCollection', count: 0, features: [] }),
          },
        },
      ],
    });
  });

  it('dice lo que la herramienta hace y, sobre todo, lo que no hace', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Observatorio');
    expect(text).toContain('No se divide una medida por otra ni se ajusta nada');
  });

  it('no manda a quien visita a ningún tercero, y lo dice', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('no envía nada a nadie sobre quien la visita');
    // Si alguna vez entra una fuente, un script o un mapa de terceros, esto se pone rojo.
    expect(element.querySelectorAll('link[href^="http"], script[src^="http"]')).toHaveLength(0);
  });
});
