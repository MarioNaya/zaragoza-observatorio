import { provideRouter } from '@angular/router';
import { TestBed } from '@angular/core/testing';

import { App } from './app';
import { routes } from './app.routes';

describe('App', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [App], providers: [provideRouter(routes)] });
  });

  it('declara su postura editorial en la cabecera, no solo su nombre', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Observatorio');
    expect(text).toContain('Herramienta de análisis, no de conclusiones');
  });

  it('ofrece las siete secciones, y el dinero va primero', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const links = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('nav a'),
    ).map((a) => a.getAttribute('href'));
    expect(links).toEqual([
      '/presupuesto',
      '/contratacion',
      '/subvenciones',
      '/quejas',
      '/actividad',
      '/territorio',
      '/catalogo',
    ]);
  });

  it('no manda a quien visita a ningún tercero, y lo dice', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('no envía nada a nadie sobre quien lo visita');
    // Si alguna vez entra una fuente, un script o un mapa de terceros, esto se pone rojo.
    const remote = element.querySelectorAll(
      'link[href^="http"], script[src^="http"], img[src^="http"], iframe',
    );
    expect(remote).toHaveLength(0);
  });
});
