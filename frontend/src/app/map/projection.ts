import { DistrictFeature, GeoJsonPolygon } from '../api/types';

/**
 * Proyección de los contornos a coordenadas de SVG (ADR-020 §4).
 *
 * Es equirectangular con la **corrección por `cos(latitud)`**, y esa corrección no es un adorno: a la latitud
 * de Zaragoza (41,65°) un grado de longitud mide 0,747 de lo que mide uno de latitud, así que dibujar lon y lat
 * a la misma escala saca la ciudad un 25 % más ancha de lo que es. Para el tamaño de un término municipal, la
 * distorsión que queda frente a una proyección de verdad es despreciable.
 */
export interface Projection {
  x(lon: number): number;
  y(lat: number): number;
  width: number;
  height: number;
}

export interface Bounds {
  minLon: number;
  maxLon: number;
  minLat: number;
  maxLat: number;
}

export function boundsOf(features: DistrictFeature[]): Bounds {
  const bounds: Bounds = {
    minLon: Infinity,
    maxLon: -Infinity,
    minLat: Infinity,
    maxLat: -Infinity,
  };
  for (const feature of features) {
    forEachPosition(feature.geometry, (lon, lat) => {
      bounds.minLon = Math.min(bounds.minLon, lon);
      bounds.maxLon = Math.max(bounds.maxLon, lon);
      bounds.minLat = Math.min(bounds.minLat, lat);
      bounds.maxLat = Math.max(bounds.maxLat, lat);
    });
  }
  return bounds;
}

export function projectionFor(bounds: Bounds, width: number, padding = 8): Projection {
  const midLat = (bounds.minLat + bounds.maxLat) / 2;
  const lonScale = Math.cos((midLat * Math.PI) / 180);

  const spanX = (bounds.maxLon - bounds.minLon) * lonScale;
  const spanY = bounds.maxLat - bounds.minLat;
  const usable = width - padding * 2;
  const scale = spanX > 0 ? usable / spanX : 1;
  const height = spanY * scale + padding * 2;

  return {
    width,
    height,
    x: (lon) => padding + (lon - bounds.minLon) * lonScale * scale,
    // El eje y del SVG crece hacia abajo y la latitud hacia arriba: se invierte.
    y: (lat) => padding + (bounds.maxLat - lat) * scale,
  };
}

/** El contorno como atributo `d` de un `<path>`. Un solo recorrido: son 16.462 vértices en total. */
export function pathOf(geometry: GeoJsonPolygon, projection: Projection): string {
  const parts: string[] = [];
  forEachRing(geometry, (ring) => {
    let d = '';
    for (let i = 0; i < ring.length; i++) {
      const [lon, lat] = ring[i];
      d += `${i === 0 ? 'M' : 'L'}${projection.x(lon).toFixed(1)} ${projection.y(lat).toFixed(1)}`;
    }
    if (d) {
      parts.push(`${d}Z`);
    }
  });
  return parts.join('');
}

/** Un punto interior razonable para poner la etiqueta: el centro del recuadro del anillo exterior mayor. */
export function labelPointOf(geometry: GeoJsonPolygon, projection: Projection): { x: number; y: number } {
  let best: number[][] = [];
  forEachRing(geometry, (ring) => {
    if (ring.length > best.length) {
      best = ring;
    }
  });
  let minLon = Infinity;
  let maxLon = -Infinity;
  let minLat = Infinity;
  let maxLat = -Infinity;
  for (const [lon, lat] of best) {
    minLon = Math.min(minLon, lon);
    maxLon = Math.max(maxLon, lon);
    minLat = Math.min(minLat, lat);
    maxLat = Math.max(maxLat, lat);
  }
  return {
    x: projection.x((minLon + maxLon) / 2),
    y: projection.y((minLat + maxLat) / 2),
  };
}

function forEachRing(geometry: GeoJsonPolygon, visit: (ring: number[][]) => void): void {
  if (geometry.type === 'Polygon') {
    for (const ring of geometry.coordinates as number[][][]) {
      visit(ring);
    }
    return;
  }
  for (const polygon of geometry.coordinates as number[][][][]) {
    for (const ring of polygon) {
      visit(ring);
    }
  }
}

function forEachPosition(geometry: GeoJsonPolygon, visit: (lon: number, lat: number) => void): void {
  forEachRing(geometry, (ring) => {
    for (const [lon, lat] of ring) {
      visit(lon, lat);
    }
  });
}
