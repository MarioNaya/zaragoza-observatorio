/**
 * El barrido visual y de accesibilidad, repetible (ADR-021 §1, ADR-022 §6).
 *
 * ADR-021 puso por regla que nada visual se entrega sin haberlo visto, y el barrido existía: rutas por anchos
 * por modos, comprobando que nada desborda en horizontal y que la consola está limpia. Pero se lanzaba a mano
 * y desde el MCP del editor, así que no era del proyecto: en una máquina sin ese MCP no había forma de mirar.
 * Ahora es `npm run sweep`, con Playwright y axe-core como dependencias de desarrollo —ninguna entra en el
 * bundle (`architecture.spec.ts` lo comprueba)—.
 *
 * Qué hace en cada combinación de ruta, ancho y modo:
 *
 * 1. captura la pantalla en `.sweep/`, que es lo único que permite *mirar*;
 * 2. comprueba que el documento no desplaza en horizontal (`scrollWidth === clientWidth`), que es el fallo que
 *    costó una versión entera: una tabla sin contenedor desbordaba 602 px en una pantalla de 375;
 * 3. recoge los errores de consola y las peticiones fallidas;
 * 4. comprueba que **no se pide nada a ningún tercero** (ADR-020 §11): toda petición va al origen propio o a
 *    la API declarada;
 * 5. pasa axe-core en el navegador de verdad —contraste incluido, que en jsdom no se puede medir— y solo con
 *    las reglas de impacto serio o crítico.
 *
 * Uso: `npm run sweep` (compila y barre), `npm run sweep -- --quick` (solo un ancho y un modo).
 */

import { createReadStream, existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';

import { chromium } from 'playwright';

const DIST = new URL('../dist/observatorio/browser/', import.meta.url);
const OUT = new URL('../.sweep/', import.meta.url);

const ROUTES = [
  ['portada', '/'],
  ['presupuesto', '/presupuesto'],
  ['contratacion', '/contratacion'],
  ['subvenciones', '/subvenciones'],
  ['quejas', '/quejas'],
  ['actividad', '/actividad'],
  ['territorio', '/territorio'],
  ['catalogo', '/catalogo'],
];

const quick = process.argv.includes('--quick');
/** 375 es el móvil pequeño donde desbordaba la tabla; 1440 el escritorio de referencia. */
const WIDTHS = quick ? [1440] : [375, 768, 1024, 1440];
const SCHEMES = quick ? ['dark'] : ['dark', 'light'];

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
};

/**
 * Servidor estático con la misma caída a `index.html` que el `.htaccess` del alojamiento: sin ella una ruta
 * profunda da 404 y el barrido mediría una pantalla que no es la que se publica.
 */
function serve(root) {
  const server = createServer((request, response) => {
    const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname)).replace(/^([/\\])+/, '');
    const file = join(root, path);
    const target = path && existsSync(file) && extname(file) ? file : join(root, 'index.html');
    response.writeHead(200, { 'content-type': TYPES[extname(target)] ?? 'application/octet-stream' });
    createReadStream(target).pipe(response);
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port }));
  });
}

const root = new URL('.', DIST).pathname.replace(/^\/([A-Za-z]:)/, '$1');
if (!existsSync(join(root, 'index.html'))) {
  throw new Error(`No hay build en ${root}. Ejecuta antes \`npm run build\`.`);
}

const axe = await readFile(new URL('../node_modules/axe-core/axe.min.js', import.meta.url), 'utf8');
await mkdir(OUT, { recursive: true });

const { server, port } = await serve(root);
const browser = await chromium.launch();
const findings = [];
let shots = 0;

for (const scheme of SCHEMES) {
  for (const width of WIDTHS) {
    const context = await browser.newContext({
      viewport: { width, height: 900 },
      colorScheme: scheme,
      deviceScaleFactor: 1,
    });
    for (const [name, route] of ROUTES) {
      const page = await context.newPage();
      const problems = [];
      const thirdParty = new Set();
      page.on('console', (message) => {
        if (message.type() === 'error') {
          problems.push(`consola: ${message.text()}`);
        }
      });
      page.on('requestfailed', (request) => {
        problems.push(`petición fallida: ${request.url()} (${request.failure()?.errorText})`);
      });
      page.on('request', (request) => {
        const url = new URL(request.url());
        const own = url.port === String(port) || url.hostname === '127.0.0.1';
        const api = url.hostname.endsWith('.up.railway.app') || url.hostname === 'localhost';
        if (!own && !api && url.protocol !== 'data:') {
          thirdParty.add(url.host);
        }
      });

      await page.goto(`http://127.0.0.1:${port}${route}`, { waitUntil: 'networkidle' });
      // El mapa y las series tardan algo más que `networkidle` en pintarse: se espera a que no haya
      // ningún «Leyendo…» a la vista, con tope, para no capturar una pantalla a medio hacer.
      await page
        .waitForFunction(() => !document.body.textContent?.includes('Leyendo'), null, { timeout: 8000 })
        .catch(() => problems.push('sigue cargando a los 8 s'));

      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }));
      if (overflow.scrollWidth > overflow.clientWidth) {
        problems.push(`desborda en horizontal: ${overflow.scrollWidth} > ${overflow.clientWidth}`);
      }

      await page.addScriptTag({ content: axe });
      const violations = await page.evaluate(async () => {
        const results = await window.axe.run(document, {
          resultTypes: ['violations'],
          runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
        });
        return results.violations
          .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
          .map((violation) => ({
            id: violation.id,
            impact: violation.impact,
            nodes: violation.nodes.slice(0, 3).map((node) => ({
              target: node.target.join(' '),
              // El detalle es la mitad del valor: en contraste trae la razón medida y los dos colores.
              why: (node.any ?? []).concat(node.all ?? []).map((check) => check.message).join(' · '),
            })),
          }));
      });

      const file = new URL(`${name}-${width}-${scheme}.png`, OUT);
      await page.screenshot({ path: file.pathname.replace(/^\/([A-Za-z]:)/, '$1'), fullPage: true });
      shots++;

      findings.push({
        route,
        width,
        scheme,
        problems,
        thirdParty: [...thirdParty],
        axe: violations,
      });
      const tag = `${name} ${width} ${scheme}`;
      const bad = problems.length + violations.length + thirdParty.size;
      console.log(`${bad === 0 ? 'ok  ' : 'AVISO'} ${tag.padEnd(28)} ${bad === 0 ? '' : JSON.stringify({ problems, thirdParty: [...thirdParty], axe: violations })}`);
      await page.close();
    }
    await context.close();
  }
}

await browser.close();
server.close();

const report = new URL('informe.json', OUT);
await writeFile(report, JSON.stringify({ takenOn: new Date().toISOString(), findings }, null, 2) + '\n', 'utf8');

const bad = findings.filter(
  (finding) => finding.problems.length > 0 || finding.axe.length > 0 || finding.thirdParty.length > 0,
);
console.log(`\n${shots} capturas en .sweep/ · ${findings.length} combinaciones · ${bad.length} con avisos`);
process.exitCode = bad.length === 0 ? 0 : 1;
