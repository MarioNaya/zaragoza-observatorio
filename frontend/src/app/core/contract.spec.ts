/**
 * Los tipos contra la respuesta real, en cada `npm test` y sin red.
 *
 * `types.ts` se escribió a mano leyendo respuestas de la instancia, y así entró el fallo que dejó tres
 * columnas del catálogo vacías: un campo con el nombre del parámetro de `sort` en vez del nombre del cuerpo.
 * El compilador no puede verlo —la respuesta es JSON— así que lo ve esto: se compara cada interfaz con la
 * **forma** grabada de la respuesta que la produce (`npm run contract`, `contract.shape.json`).
 *
 * Dos reglas, asimétricas a propósito (ADR-022 §4):
 *
 * 1. **Todo campo declarado tiene que existir** en la respuesta. Declarar menos campos que la respuesta está
 *    permitido —se elige qué se usa—; declarar uno que no existe es la mentira que se paga en pantalla.
 * 2. **Si la muestra lo trajo nulo, el tipo tiene que admitir el nulo.** Al revés no: que tres filas no traigan
 *    nulo no demuestra que no pueda llegar, así que un `| null` de más nunca falla.
 */

import shapeFile from './contract.shape.json';

type Shape = string | { [key: string]: Shape };

interface Field {
  name: string;
  optional: boolean;
  type: string;
}

const sources = import.meta.glob('./types.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

const typesSource = Object.values(sources)[0]!;

/**
 * Dónde mirar la forma de cada interfaz: `clave-de-endpoint#ruta`, con `[]` para entrar en un array.
 *
 * El mapa tiene que estar completo: el test comprueba que no queda ninguna interfaz sin muestra, así que un
 * tipo nuevo no puede entrar sin que alguien diga de qué respuesta sale.
 */
const SAMPLES: Record<string, string> = {
  Source: 'citizen/summary#source',
  ApiItem: 'citizen/summary#',
  ApiPage: 'citizen/requests#',
  CatalogPage: 'catalog/datasets#',
  GeoJsonPolygon: 'geo/boundaries#features[].geometry',
  DistrictFeature: 'geo/boundaries#features[]',
  DistrictBoundaries: 'geo/boundaries#',
  Coverage: 'territory/districts#item.measures[].coverage',
  MeasureColumn: 'territory/districts#item.measures[]',
  DistrictRow: 'territory/districts#item.items[]',
  CrossTab: 'territory/districts#item',
  DistrictCard: 'territory/district#item',
  AssignmentBreakdown: 'citizen/summary#item.assignment',
  CitizenSummary: 'citizen/summary#item',
  ServiceRequest: 'citizen/requests#items[]',
  CitizenBucket: 'citizen/aggregations#item.buckets[]',
  YearCoverage: 'citizen/aggregations#item.coverageByYear[]',
  CitizenAggregation: 'citizen/aggregations#item',
  UrbanSummary: 'urban/summary#item',
  Licence: 'urban/premises#items[].licences[]',
  Premises: 'urban/premises#items[]',
  UrbanBucket: 'urban/aggregations#item.buckets[]',
  UrbanAggregation: 'urban/aggregations#item',
  SpendingSummary: 'spending/summary#item',
  Cpv: 'spending/processes#items[].cpv[]',
  ContractingProcess: 'spending/processes#items[]',
  SpendingBucket: 'spending/aggregations#item.buckets[]',
  SpendingAggregation: 'spending/aggregations#item',
  BudgetAmounts: 'budget/summary#item.latestAmounts',
  BudgetSummary: 'budget/summary#item',
  BudgetLine: 'budget/lines#items[]',
  BudgetBucket: 'budget/aggregations#item.items[]',
  BudgetAggregation: 'budget/aggregations#item',
  GrantsSummary: 'grants/summary#item',
  Grant: 'grants/list#items[]',
  GrantBucket: 'grants/aggregations#item.buckets[]',
  GrantAggregation: 'grants/aggregations#item',
  CatalogSummary: 'catalog/summary#',
  Dataset: 'catalog/datasets#items[]',
  /** El estado de la pantalla del cruce, no un cuerpo de respuesta: se pide, no se recibe. */
  CrossTabQuery: '',
};

/** Las interfaces de `types.ts`, con sus campos tal como están escritos. */
function parseInterfaces(source: string): Map<string, Field[]> {
  const interfaces = new Map<string, Field[]>();
  const lines = source.split('\n');
  let current: string | null = null;
  let fields: Field[] = [];

  for (const line of lines) {
    const opening = /^export interface (\w+)(?:<[^>]*>)? \{$/.exec(line);
    if (opening) {
      current = opening[1]!;
      fields = [];
      continue;
    }
    if (current === null) {
      continue;
    }
    if (line === '}') {
      interfaces.set(current, fields);
      current = null;
      continue;
    }
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('/*') || trimmed.startsWith('*') || trimmed.startsWith('//')) {
      continue;
    }
    const field = /^ {2}(\w+)(\?)?: (.+);$/.exec(line);
    if (!field) {
      // Ni una línea se salta en silencio: si el analizador no la entiende, el test lo dice.
      throw new Error(`No se entiende esta línea de ${current}: «${line}»`);
    }
    fields.push({ name: field[1]!, optional: field[2] === '?', type: field[3]! });
  }
  return interfaces;
}

function resolve(reference: string): Shape | 'sin-muestra' {
  const [endpoint, path = ''] = reference.split('#');
  const recorded = (shapeFile.endpoints as Record<string, { shape: Shape } | undefined>)[endpoint!];
  if (!recorded) {
    return 'sin-muestra';
  }
  let node: Shape = recorded.shape;
  for (const segment of path.split('.').filter(Boolean)) {
    const key = segment.replace('[]', '');
    if (typeof node === 'string' || !(key in node)) {
      return 'sin-muestra';
    }
    node = node[key]!;
    if (segment.endsWith('[]')) {
      if (typeof node === 'string' || !('[]' in node)) {
        return 'sin-muestra';
      }
      node = node['[]']!;
    }
  }
  return node;
}

const PRIMITIVE = /^(string|number|boolean)(\s*\|\s*null)?$/;

const interfaces = parseInterfaces(typesSource);

describe('los tipos contra la forma grabada de la API', () => {
  it('sabe de qué respuesta sale cada interfaz', () => {
    const unmapped = [...interfaces.keys()].filter((name) => !(name in SAMPLES));
    expect(unmapped).toEqual([]);
  });

  it('tiene la forma grabada de las veinte respuestas que sostienen los tipos', () => {
    expect(Object.keys(shapeFile.endpoints)).toHaveLength(20);
    expect(shapeFile.recordedAgainst).toContain('http');
  });

  it('no declara ningún campo que la respuesta no traiga', () => {
    const offences: string[] = [];
    for (const [name, fields] of interfaces) {
      const reference = SAMPLES[name]!;
      if (reference === '') {
        continue;
      }
      const shape = resolve(reference);
      if (shape === 'sin-muestra' || typeof shape === 'string') {
        offences.push(`${name}: la muestra ${reference} no está en contract.shape.json`);
        continue;
      }
      for (const field of fields) {
        if (!(field.name in shape) && !field.optional) {
          offences.push(`${name}.${field.name} no existe en la respuesta (${reference})`);
        }
      }
    }
    expect(offences).toEqual([]);
  });

  it('admite el nulo donde la respuesta lo trae, y el tipo primitivo que llega', () => {
    const offences: string[] = [];
    for (const [name, fields] of interfaces) {
      const reference = SAMPLES[name]!;
      if (reference === '') {
        continue;
      }
      const shape = resolve(reference);
      if (typeof shape !== 'object') {
        continue;
      }
      for (const field of fields) {
        const node = shape[field.name];
        if (node === undefined || typeof node !== 'string' || node === 'unknown') {
          continue;
        }
        const observed = node.split('|');
        if (observed.includes('null') && !/null/.test(field.type) && !field.optional) {
          offences.push(`${name}.${field.name} llega nulo y el tipo dice «${field.type}»`);
        }
        if (PRIMITIVE.test(field.type)) {
          for (const type of observed) {
            if (type !== 'null' && !field.type.includes(type)) {
              offences.push(`${name}.${field.name} llega como ${type} y el tipo dice «${field.type}»`);
            }
          }
        }
      }
    }
    expect(offences).toEqual([]);
  });
});
