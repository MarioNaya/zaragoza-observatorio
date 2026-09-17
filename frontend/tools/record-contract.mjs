/**
 * Graba la **forma** de las respuestas de la API en `src/app/core/contract.shape.json`.
 *
 * Es el equivalente de los fixtures de los spikes del backend (regla 16), con una diferencia importante: aquí
 * no se guarda ni un valor. De cada respuesta se queda el nombre de cada campo y el tipo de su contenido
 * (`string`, `number`, `null`, `string|null`…), así que no puede colarse un dato personal en el repositorio
 * (regla 22) y el fichero no envejece por el contenido, solo por el contrato.
 *
 * Se ejecuta a mano, como un spike: `npm run contract` contra la instancia, o
 * `npm run contract -- http://localhost:8085` contra la de desarrollo. Lo que lo comprueba en cada `npm test`
 * es `src/app/core/contract.spec.ts`, sin red.
 */

import { writeFile } from 'node:fs/promises';

const base = (process.argv[2] ?? 'https://observatorio-production-ed20.up.railway.app').replace(/\/$/, '');

/**
 * Las respuestas que sostienen los tipos. Cada clave es la que usa `contract.spec.ts`, así que si aquí se
 * quita una, el test lo dice en vez de dejar de comprobar en silencio.
 *
 * Los listados se piden con `size=3`: lo que hace falta es la forma, y tres filas ya enseñan qué campos llegan
 * nulos en unas y no en otras.
 */
const ENDPOINTS = {
  'citizen/summary': '/citizen/summary',
  'citizen/requests': '/citizen/requests?size=3&sort=requestedAt,desc',
  'citizen/aggregations': '/citizen/aggregations?by=district_year',
  'urban/summary': '/urban/summary',
  'urban/premises': '/urban/premises?size=3&sort=createdAt,desc',
  'urban/aggregations': '/urban/aggregations?by=licence_year',
  'spending/summary': '/spending/summary',
  'spending/processes': '/spending/processes?size=3&sort=publishedAt,desc',
  'spending/aggregations': '/spending/aggregations?by=year',
  'budget/summary': '/spending/budget/summary',
  'budget/lines': '/spending/budget/lines?size=3&sort=obligations,desc',
  'budget/aggregations': '/spending/budget/aggregations?by=year',
  'grants/summary': '/spending/grants/summary',
  'grants/list': '/spending/grants?size=3&sort=granted,desc',
  'grants/aggregations': '/spending/grants/aggregations?by=year',
  'catalog/summary': '/catalog/summary',
  'catalog/datasets': '/catalog/datasets?size=3&sort=title,asc',
  'territory/districts':
    '/territory/districts?measures=citizen.requests,urban.premises,urban.licences&denominator=population',
  'territory/district':
    '/territory/districts/6?measures=citizen.requests,urban.premises,urban.licences&denominator=population',
  'geo/boundaries': '/geo/boundaries',
};

/** El tipo de un valor, nunca el valor. Un array colapsa en la forma común de sus elementos. */
function shapeOf(value) {
  if (value === null) {
    return 'null';
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return { '[]': 'unknown' };
    }
    return { '[]': value.map(shapeOf).reduce(merge) };
  }
  if (typeof value === 'object') {
    const shape = {};
    for (const [key, nested] of Object.entries(value)) {
      shape[key] = shapeOf(nested);
    }
    return shape;
  }
  return typeof value;
}

function merge(left, right) {
  if (typeof left === 'string' && typeof right === 'string') {
    const types = new Set([...left.split('|'), ...right.split('|')]);
    types.delete('unknown');
    return [...types].sort().join('|') || 'unknown';
  }
  if (typeof left === 'string') {
    return left === 'null' ? withNull(right) : right;
  }
  if (typeof right === 'string') {
    return right === 'null' ? withNull(left) : left;
  }
  const shape = { ...left };
  for (const [key, nested] of Object.entries(right)) {
    shape[key] = key in left ? merge(left[key], nested) : nested;
  }
  return shape;
}

/** Un objeto que en algún elemento del array llegó nulo: se marca y el tipo lo tiene que admitir. */
function withNull(shape) {
  return typeof shape === 'string' ? merge(shape, 'null') : { ...shape, '#nullable': true };
}

const recorded = {};
for (const [name, path] of Object.entries(ENDPOINTS)) {
  const url = `${base}/api/v1${path}`;
  // Sin `accept: application/json`: los contornos se sirven como `application/geo+json` y con esa cabecera
  // responden 406. El `HttpClient` del navegador manda `*/*` y por eso la pantalla nunca lo notó.
  const response = await fetch(url, { headers: { accept: '*/*' } });
  if (!response.ok) {
    throw new Error(`${name}: ${response.status} en ${url}`);
  }
  recorded[name] = { path, shape: shapeOf(await response.json()) };
  console.log(`${name} ← ${path}`);
}

const file = new URL('../src/app/core/contract.shape.json', import.meta.url);
await writeFile(
  file,
  JSON.stringify(
    {
      recordedAgainst: base,
      recordedOn: new Date().toISOString().slice(0, 10),
      endpoints: recorded,
    },
    null,
    2,
  ) + '\n',
  'utf8',
);
console.log(`\n${Object.keys(recorded).length} respuestas grabadas en src/app/core/contract.shape.json`);
