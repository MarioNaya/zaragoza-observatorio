import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { routes } from '../app.routes';
import { CatalogPage } from './catalog';
import { CitizenPage } from './citizen';
import { HomePage } from './home';

/**
 * Que una pantalla **pinte lo que la API devuelve**, y que diga la verdad cuando no puede leerla.
 *
 * Los quince tests que había cubrían lógica —clasificación, contrato de la tabla, armazón— y ninguno cubría
 * esto, que es donde estaban los dos fallos que la auditoría encontró: tres columnas del catálogo leyendo
 * campos que no existen y un «cargando» que no se distinguía de un vacío (ADR-022 §4 y §5).
 *
 * Se responde a las peticiones con cuerpos reales recortados, con los nombres de campo de la instancia.
 */
function setUp(): HttpTestingController {
  TestBed.configureTestingModule({
    providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
  });
  return TestBed.inject(HttpTestingController);
}

const CITIZEN_SUMMARY = {
  source: { dataset: 'sede:quejas-sugerencias', url: 'https://example.invalid/quejas.json' },
  ingestedAt: '2026-09-16T20:00:00Z',
  caveats: ['El listado abierto no son todas las quejas.'],
  item: {
    total: 100,
    internal: 3,
    earliestRequestedAt: '2013-01-08T00:00:00Z',
    latestRequestedAt: '2026-09-16T00:00:00Z',
    latestUpdatedAt: '2026-09-16T00:00:00Z',
    byStatus: { OPEN: 40, CLOSED: 60 },
    assignment: {
      byAssignment: { RESOLVED: 30, NO_POINT: 70 },
      declaredAgrees: 0,
      declaredDisagrees: 0,
      declaredOnly: 0,
      declaredUnmatched: 0,
    },
  },
};

const CITIZEN_REQUESTS = {
  caveats: [],
  total: 2,
  page: 0,
  size: 25,
  items: [
    {
      id: 958234,
      status: 'OPEN',
      serviceCode: '124',
      serviceName: 'Contenedor roto',
      requestedAt: '2026-09-16T10:00:00Z',
      closedAt: null,
      responseHours: null,
      lon: null,
      lat: null,
      assignment: 'NO_POINT',
      districtId: null,
      districtName: null,
      districtDeclared: null,
      districtDeclaredId: null,
    },
    {
      id: 958188,
      status: 'CLOSED',
      serviceCode: '7',
      serviceName: 'Alumbrado Público',
      requestedAt: '2026-09-15T10:00:00Z',
      closedAt: '2026-09-16T10:00:00Z',
      responseHours: 24,
      lon: -0.88,
      lat: 41.65,
      assignment: 'RESOLVED',
      districtId: 6,
      districtName: 'Centro',
      districtDeclared: 'CENTRO',
      districtDeclaredId: 6,
    },
  ],
};

const CITIZEN_AGGREGATION = {
  caveats: [],
  item: {
    by: 'month',
    buckets: [
      {
        key: '2026-09',
        label: null,
        year: null,
        total: 100,
        closed: 60,
        open: 40,
        withPoint: 30,
        pointCoverage: 0.3,
        internal: 3,
        medianResponseHours: 24,
        population: null,
        populationYear: null,
        perThousandInhabitants: null,
      },
    ],
    coverageByYear: [],
    assignment: {
      byAssignment: { RESOLVED: 30 },
      declaredAgrees: 0,
      declaredDisagrees: 0,
      declaredOnly: 0,
      declaredUnmatched: 0,
    },
    matched: 100,
    unassigned: 70,
    internal: 3,
  },
};

describe('la pantalla de quejas', () => {
  it('pinta las filas y el total que devuelve la API', async () => {
    const http = setUp();
    const fixture = TestBed.createComponent(CitizenPage);

    http.expectOne((request) => request.url.endsWith('/citizen/summary')).flush(CITIZEN_SUMMARY);
    http.expectOne((request) => request.url.includes('/citizen/aggregations')).flush(CITIZEN_AGGREGATION);
    http.expectOne((request) => request.url.includes('/citizen/requests')).flush(CITIZEN_REQUESTS);
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Contenedor roto');
    expect(text).toContain('Alumbrado Público');
    // La junta resuelta se enseña como tal y la ausencia de punto se dice, no se deja en blanco.
    expect(text).toContain('junta resuelta: Centro');
    expect(text).toContain('sin coordenadas');
    expect(text).toContain('1–2 de 2 quejas');
    http.verify();
  });

  it('pide siempre con orden, página y tamaño: ordena y pagina el backend', () => {
    const http = setUp();
    TestBed.createComponent(CitizenPage);

    http.expectOne((request) => request.url.endsWith('/citizen/summary')).flush(CITIZEN_SUMMARY);
    http.expectOne((request) => request.url.includes('/citizen/aggregations')).flush(CITIZEN_AGGREGATION);
    const listing = http.expectOne((request) => request.url.includes('/citizen/requests'));
    expect(listing.request.params.get('sort')).toBe('requestedAt,desc');
    expect(listing.request.params.get('page')).toBe('0');
    expect(listing.request.params.get('size')).toBe('25');
    listing.flush(CITIZEN_REQUESTS);
    http.verify();
  });

  it('cuando el listado falla lo dice, no dice que no hay registros', async () => {
    const http = setUp();
    const fixture = TestBed.createComponent(CitizenPage);

    http.expectOne((request) => request.url.endsWith('/citizen/summary')).flush(CITIZEN_SUMMARY);
    http.expectOne((request) => request.url.includes('/citizen/aggregations')).flush(CITIZEN_AGGREGATION);
    http
      .expectOne((request) => request.url.includes('/citizen/requests'))
      .flush({ detail: 'El parámetro sort no acepta ese campo.' }, { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    const text = element.textContent ?? '';
    expect(text).toContain('No se ha podido leer las quejas');
    expect(text).toContain('El parámetro sort no acepta ese campo.');
    expect(text).not.toContain('Ningún registro casa con estos filtros');
    expect(element.querySelector('[role="alert"]')).not.toBeNull();

    // Y se puede volver a intentar: el botón vuelve a pedir lo mismo.
    const retry = element.querySelector('.failed button') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    retry.click();
    http.expectOne((request) => request.url.includes('/citizen/requests')).flush(CITIZEN_REQUESTS);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Contenedor roto');
    http.verify();
  });
});

const CATALOG_SUMMARY = {
  source: { dataset: 'sede:catalogo', url: 'https://example.invalid/catalogo.json' },
  ingestedAt: '2026-09-17T06:44:00Z',
  caveats: [],
  datasets: 438,
  byDeclaredFreshness: { ON_TIME: 100, NOT_EVALUABLE: 265 },
  byPeriodicity: { P1Y: 100 },
  withApi: 200,
  open: 300,
  explorable: 50,
  withGeo: 70,
  latestSnapshotOn: '2026-09-17',
  withoutSnapshot: 0,
  byObservationMethod: { API_MAX_DATE: 200, NOT_OBSERVABLE: 60 },
  withoutObservation: 0,
  notListed: 4,
  apiInventory: { endpoints: 700 },
  federation: { notInCatalog: 108 },
  thresholds: { onTimeMax: 1 },
};

const CATALOG_DATASETS = {
  source: { dataset: 'sede:catalogo', url: 'https://example.invalid/catalogo.json' },
  ingestedAt: '2026-09-17T06:44:00Z',
  caveats: [],
  page: { number: 0, size: 25, totalElements: 438, totalPages: 18, sort: 'title,asc' },
  items: [
    {
      id: 1780,
      title: 'Tablón de Edictos',
      issued: '2017-05-29T00:00:00',
      declaredModified: '2017-05-29T00:00:00',
      metadataUpdated: '2026-01-20T13:12:38',
      declaredPeriodicity: 'P0DT1S',
      periodicityDays: null,
      publicationStatus: 'Finalizado',
      hasGeo: false,
      open: true,
      explorable: false,
      hasApi: true,
      apiTag: 'Ayuntamiento: Tablón de edictos',
      federated: true,
      federatedUrl: 'https://example.invalid/tablon',
      latestFreshness: 'NOT_EVALUABLE',
      latestRatio: null,
      latestSnapshotOn: '2026-09-17',
      observedAt: '2026-09-16T23:35:34Z',
      latestObservationMethod: 'API_MAX_DATE',
      latestObservedChange: '2026-09-16T22:10:01Z',
      firstSeenAt: '2026-09-06T22:26:37Z',
      lastSeenAt: '2026-09-17T06:44:11Z',
      listed: true,
      delistedAt: null,
    },
  ],
};

describe('la pantalla del catálogo', () => {
  /**
   * La regresión que más importa de toda la auditoría: estas tres columnas leían `observationMethod`,
   * `observedLastChange` y `declaredFreshness`, que no existen en la respuesta. Salían vacías en la pantalla
   * publicada y nada fallaba.
   */
  it('pinta el método observado, la fecha observada y la frescura declarada', async () => {
    const http = setUp();
    const fixture = TestBed.createComponent(CatalogPage);

    http.expectOne((request) => request.url.endsWith('/catalog/summary')).flush(CATALOG_SUMMARY);
    http.expectOne((request) => request.url.includes('/catalog/datasets')).flush(CATALOG_DATASETS);
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Tablón de Edictos');
    expect(text).toContain('Fecha máxima en la API');
    expect(text).toContain('No evaluable');
    // La fecha observada, formateada en español a partir de `latestObservedChange`. Sale en **hora local**:
    // el instante es 2026-09-16T22:10:01Z y en Zaragoza son las 00:10 del día siguiente.
    expect(text).toContain('17 sept 2026');
    http.verify();
  });
});

describe('la portada', () => {
  it('dice qué resumen no se pudo leer en vez de dejar un guion', async () => {
    const http = setUp();
    const fixture = TestBed.createComponent(HomePage);

    // Los seis resúmenes se piden en paralelo; falla uno y los demás llegan.
    http
      .expectOne((request) => request.url.endsWith('/spending/budget/summary'))
      .flush({}, { status: 503, statusText: 'Service Unavailable' });
    for (const path of [
      '/spending/summary',
      '/spending/grants/summary',
      '/citizen/summary',
      '/urban/summary',
      '/catalog/summary',
    ]) {
      http.expectOne((request) => request.url.endsWith(path)).flush(path === '/catalog/summary' ? CATALOG_SUMMARY : CITIZEN_SUMMARY);
    }
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No se ha podido leer el presupuesto de gastos');
    http.verify();
  });
});
