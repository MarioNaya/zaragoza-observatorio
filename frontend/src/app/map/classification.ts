/**
 * Clasificación del mapa (ADR-020 §6). Los tres métodos que `SPEC.md` §1 pone sobre la mesa, ninguno
 * escondido: el nombre del que está en uso y los cortes reales van escritos en la leyenda, porque **los mismos
 * datos sugieren cosas distintas** según el que se elija.
 *
 * Todo esto vive en el navegador y no en el backend a propósito: clasificar es pintar, y pintar es del
 * frontend (regla 8). El backend no tiene que saber en cuántas clases se va a partir su columna.
 */

export type ClassificationMethod = 'quantiles' | 'equal' | 'jenks';

export const CLASSIFICATION_LABELS: Record<ClassificationMethod, string> = {
  quantiles: 'Cuantiles',
  equal: 'Intervalos iguales',
  jenks: 'Cortes naturales',
};

export const CLASSIFICATION_HINTS: Record<ClassificationMethod, string> = {
  quantiles: 'Mismo número de juntas por clase. Enseña el orden.',
  equal: 'Clases del mismo ancho. Enseña la magnitud.',
  jenks: 'Cortes donde el dato se agrupa solo.',
};

/** Número de clases. Cinco es lo que admite una leyenda legible con 29 unidades. */
export const CLASS_COUNT = 5;

/**
 * El resultado de clasificar: los umbrales superiores de cada clase y la clase de cada valor.
 * `breaks` tiene `classes` elementos y el último es el máximo.
 */
export interface Classification {
  method: ClassificationMethod;
  breaks: number[];
  min: number;
  max: number;
  /** Cuántas juntas caen en cada clase; va en la leyenda para que se vea el reparto. */
  counts: number[];
}

/** La clase de un valor, 0..classes-1. Un valor sin dato no tiene clase y se pinta aparte. */
export function classOf(value: number, classification: Classification): number {
  const { breaks } = classification;
  for (let i = 0; i < breaks.length; i++) {
    if (value <= breaks[i]) {
      return i;
    }
  }
  return breaks.length - 1;
}

export function classify(
  values: number[],
  method: ClassificationMethod,
  classes: number = CLASS_COUNT,
): Classification {
  const clean = values.filter((value) => Number.isFinite(value)).sort((a, b) => a - b);
  if (clean.length === 0) {
    return { method, breaks: [], min: 0, max: 0, counts: [] };
  }
  const min = clean[0];
  const max = clean[clean.length - 1];

  // Todos iguales: una sola clase, y decirlo es más honesto que inventar cinco tramos vacíos.
  if (min === max) {
    return { method, breaks: [max], min, max, counts: [clean.length] };
  }

  const breaks = uniqueAscending(
    method === 'equal'
      ? equalBreaks(min, max, classes)
      : method === 'jenks'
        ? jenksBreaks(clean, classes)
        : quantileBreaks(clean, classes),
    max,
  );
  const classification: Classification = { method, breaks, min, max, counts: [] };
  classification.counts = countPerClass(clean, classification);
  return classification;
}

/** Cada clase con el mismo número de juntas. */
function quantileBreaks(sorted: number[], classes: number): number[] {
  const breaks: number[] = [];
  for (let i = 1; i <= classes; i++) {
    const position = (sorted.length * i) / classes - 1;
    breaks.push(sorted[Math.min(sorted.length - 1, Math.max(0, Math.round(position)))]);
  }
  return breaks;
}

/** Cada clase del mismo ancho. */
function equalBreaks(min: number, max: number, classes: number): number[] {
  const width = (max - min) / classes;
  const breaks: number[] = [];
  for (let i = 1; i <= classes; i++) {
    breaks.push(min + width * i);
  }
  return breaks;
}

/**
 * Cortes naturales de Jenks: los que minimizan la varianza dentro de cada clase. Implementado con la
 * programación dinámica clásica, que con 29 valores y 5 clases es instantánea.
 */
function jenksBreaks(sorted: number[], classes: number): number[] {
  const n = sorted.length;
  if (n <= classes) {
    return sorted.slice();
  }

  // variance[i][k] = menor suma de desviaciones cuadráticas de los primeros i valores en k clases.
  const variance: number[][] = matrix(n + 1, classes + 1, Infinity);
  const boundary: number[][] = matrix(n + 1, classes + 1, 0);
  variance[0][0] = 0;

  for (let i = 1; i <= n; i++) {
    for (let k = 1; k <= Math.min(classes, i); k++) {
      let sum = 0;
      let squares = 0;
      let count = 0;
      // Se recorre hacia atrás para acumular la clase que termina en i sin recalcularla entera.
      for (let j = i; j >= k; j--) {
        const value = sorted[j - 1];
        count++;
        sum += value;
        squares += value * value;
        const deviation = squares - (sum * sum) / count;
        const previous = variance[j - 1][k - 1];
        if (previous + deviation < variance[i][k]) {
          variance[i][k] = previous + deviation;
          boundary[i][k] = j - 1;
        }
      }
    }
  }

  const breaks: number[] = [];
  let end = n;
  for (let k = classes; k >= 1; k--) {
    breaks.unshift(sorted[end - 1]);
    end = boundary[end][k];
  }
  return breaks;
}

function matrix(rows: number, columns: number, fill: number): number[][] {
  return Array.from({ length: rows }, () => new Array<number>(columns).fill(fill));
}

/**
 * Quita cortes repetidos. Pasa cuando el dato se concentra —muchas juntas con el mismo valor— y dejarlos
 * produciría clases vacías en la leyenda, que es enseñar un tramo que no existe.
 */
function uniqueAscending(breaks: number[], max: number): number[] {
  const unique: number[] = [];
  for (const value of breaks) {
    if (unique.length === 0 || value > unique[unique.length - 1]) {
      unique.push(value);
    }
  }
  if (unique.length === 0 || unique[unique.length - 1] < max) {
    unique.push(max);
  }
  return unique;
}

function countPerClass(sorted: number[], classification: Classification): number[] {
  const counts = new Array<number>(classification.breaks.length).fill(0);
  for (const value of sorted) {
    counts[classOf(value, classification)]++;
  }
  return counts;
}
