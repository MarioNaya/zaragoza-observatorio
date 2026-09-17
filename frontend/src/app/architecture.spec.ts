/**
 * Las fronteras del frontend, comprobadas en cada `npm test`.
 *
 * El backend tiene `ModularityTests` y `HexagonalArchitectureTests` vigilando sus módulos en cada build; aquí
 * había cuatro carpetas y ninguna regla, así que nada impedía que una página importara de otra, que `ui/`
 * pidiera datos o que entrara una librería de gráficos. Esto es el equivalente, con las mismas reglas escritas
 * de ADR-020 y ADR-021 (ADR-022 §2).
 *
 * Se lee el código fuente con `import.meta.glob(..., '?raw')` en vez de `node:fs` para no depender de los tipos
 * de Node en los tests: lo que entra aquí es el texto de cada fichero, igual que lo lee quien revisa.
 */

type Layer = 'root' | 'core' | 'ui' | 'map' | 'pages';

const sources = import.meta.glob('./**/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** El código de la aplicación, sin los propios tests. */
const files = Object.entries(sources)
  .filter(([path]) => !path.endsWith('.spec.ts'))
  .map(([path, source]) => ({ path: path.replace(/^\.\//, ''), source }));

/** Terceros permitidos en tiempo de ejecución: Angular y su rxjs, y nada más (ADR-020 §3). */
const ALLOWED_PACKAGES = [/^@angular\//, /^rxjs(\/|$)/, /^tslib$/];

const LAYER_RULES: Record<Layer, Layer[]> = {
  // `core` no conoce a nadie: es el dato y su formato.
  core: ['core'],
  // `ui` y `map` pintan lo que les dan. Pueden leer el contrato y el formato, nunca pedir nada.
  ui: ['core', 'ui'],
  map: ['core', 'map'],
  // Una página compone: puede usar todo lo de abajo y sus propias piezas de sección.
  pages: ['core', 'ui', 'map', 'pages'],
  // El armazón solo conoce las rutas y las páginas que carga, y las carga con `loadComponent`.
  root: ['root', 'core', 'pages'],
};

function layerOf(path: string): Layer {
  if (path.startsWith('core/')) {
    return 'core';
  }
  if (path.startsWith('ui/')) {
    return 'ui';
  }
  if (path.startsWith('map/')) {
    return 'map';
  }
  if (path.startsWith('pages/')) {
    return 'pages';
  }
  return 'root';
}

/** Los especificadores de `import` de un fichero, incluidos los dinámicos de las rutas. */
function importsOf(source: string): string[] {
  const specifiers: string[] = [];
  const statics = /^\s*import\s[^;]*?from\s+'([^']+)'/gm;
  const dynamics = /import\(\s*'([^']+)'\s*\)/g;
  for (const match of source.matchAll(statics)) {
    specifiers.push(match[1]!);
  }
  for (const match of source.matchAll(dynamics)) {
    specifiers.push(match[1]!);
  }
  return specifiers;
}

/** A qué capa apunta un import relativo, resuelto desde el fichero que lo escribe. */
function targetLayer(fromPath: string, specifier: string): Layer | 'environments' | 'package' {
  if (!specifier.startsWith('.')) {
    return 'package';
  }
  const parts = fromPath.split('/').slice(0, -1);
  for (const segment of specifier.split('/')) {
    if (segment === '.') {
      continue;
    }
    if (segment === '..') {
      parts.pop();
      continue;
    }
    parts.push(segment);
  }
  const resolved = parts.join('/');
  return resolved.startsWith('environments/') ? 'environments' : layerOf(resolved);
}

describe('las fronteras del frontend', () => {
  it('encuentra el código que tiene que vigilar', () => {
    // Si el glob deja de ver los fuentes, todo lo de abajo pasaría en verde sin comprobar nada.
    expect(files.length).toBeGreaterThan(20);
    expect(files.map((file) => file.path)).toContain('core/api.ts');
  });

  it('respeta las capas: nadie importa hacia arriba', () => {
    const offences: string[] = [];
    for (const file of files) {
      const from = layerOf(file.path);
      for (const specifier of importsOf(file.source)) {
        const to = targetLayer(file.path, specifier);
        if (to === 'package' || to === 'environments') {
          continue;
        }
        if (!LAYER_RULES[from].includes(to)) {
          offences.push(`${file.path} (${from}) → ${specifier} (${to})`);
        }
      }
    }
    expect(offences).toEqual([]);
  });

  it('no mete ninguna dependencia de tiempo de ejecución que no sea Angular', () => {
    const offences: string[] = [];
    for (const file of files) {
      for (const specifier of importsOf(file.source)) {
        if (specifier.startsWith('.')) {
          continue;
        }
        if (!ALLOWED_PACKAGES.some((allowed) => allowed.test(specifier))) {
          offences.push(`${file.path} → ${specifier}`);
        }
      }
    }
    expect(offences).toEqual([]);
  });

  it('deja las URL y el cliente HTTP en un solo sitio', () => {
    const offences: string[] = [];
    for (const file of files) {
      const isApi = file.path === 'core/api.ts';
      const isConfig = file.path === 'app.config.ts';
      if (!isApi && /\bHttpClient\b|\bHttpParams\b/.test(file.source) && !isConfig) {
        offences.push(`${file.path} usa el cliente HTTP`);
      }
      if (!isApi && /from '.*environments\/environment'/.test(file.source)) {
        offences.push(`${file.path} lee la configuración de entorno`);
      }
      if (!file.path.startsWith('environments/') && /'https?:\/\//.test(file.source)) {
        offences.push(`${file.path} escribe una URL a mano`);
      }
      if (/\bfetch\(|XMLHttpRequest/.test(file.source)) {
        offences.push(`${file.path} pide datos por su cuenta`);
      }
    }
    expect(offences).toEqual([]);
  });

  it('no deja que `ui/` ni `map/` pidan datos: reciben lo que pintan', () => {
    const offences = files
      .filter((file) => file.path.startsWith('ui/') || file.path.startsWith('map/'))
      .filter((file) => /\bObservatory\b/.test(file.source))
      .map((file) => file.path);
    expect(offences).toEqual([]);
  });

  it('no deja que una sección importe otra sección', () => {
    const routes = files.find((file) => file.path === 'app.routes.ts');
    expect(routes).toBeDefined();
    // Las rutas son la definición de qué es una sección: lo que se carga con `loadComponent` es una.
    const routed = importsOf(routes!.source)
      .filter((specifier) => specifier.startsWith('./pages/'))
      .map((specifier) => specifier.replace('./', '') + '.ts');
    expect(routed.length).toBeGreaterThanOrEqual(7);

    const offences: string[] = [];
    for (const page of files.filter((file) => routed.includes(file.path))) {
      for (const specifier of importsOf(page.source)) {
        const resolved = specifier.startsWith('./') ? `pages/${specifier.replace('./', '')}.ts` : null;
        if (resolved && routed.includes(resolved)) {
          offences.push(`${page.path} → ${resolved}`);
        }
      }
    }
    expect(offences).toEqual([]);
  });

  it('carga cada sección aparte, sin importarla en el armazón', () => {
    const shell = files.filter((file) => layerOf(file.path) === 'root' && file.path !== 'app.routes.ts');
    const offences = shell
      .filter((file) => /^\s*import\s[^;]*?from\s+'\.\/pages\//m.test(file.source))
      .map((file) => file.path);
    expect(offences).toEqual([]);
  });
});
