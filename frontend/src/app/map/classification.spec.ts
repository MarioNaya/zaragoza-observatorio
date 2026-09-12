import { classOf, classify } from './classification';

/**
 * Los tres métodos de ADR-020 §6. Lo que se comprueba no es que existan sino que **dan mapas distintos con los
 * mismos datos**, que es la razón entera por la que el método va escrito en la leyenda.
 */
describe('classify', () => {
  // Reparto sesgado, como el real: muchas juntas bajas y dos altas.
  const skewed = [1, 2, 2, 3, 4, 5, 6, 8, 9, 12, 14, 18, 60, 95];

  it('reparte las unidades por igual con cuantiles', () => {
    const quantiles = classify(skewed, 'quantiles', 5);

    expect(quantiles.counts.every((count) => count >= 2)).toBe(true);
    expect(quantiles.counts.reduce((a, b) => a + b, 0)).toBe(skewed.length);
  });

  it('con intervalos iguales el sesgo amontona casi todo en la primera clase', () => {
    const equal = classify(skewed, 'equal', 5);

    expect(equal.counts[0]).toBeGreaterThan(equal.counts.slice(1).reduce((a, b) => a + b, 0));
  });

  it('los tres métodos no dan los mismos cortes: por eso el método se nombra', () => {
    const quantiles = classify(skewed, 'quantiles', 5).breaks;
    const equal = classify(skewed, 'equal', 5).breaks;
    const jenks = classify(skewed, 'jenks', 5).breaks;

    expect(quantiles).not.toEqual(equal);
    expect(jenks).not.toEqual(equal);
  });

  it('cada valor cae en una clase y el mayor en la última', () => {
    const classification = classify(skewed, 'jenks', 5);

    for (const value of skewed) {
      const klass = classOf(value, classification);
      expect(klass).toBeGreaterThanOrEqual(0);
      expect(klass).toBeLessThan(classification.breaks.length);
    }
    expect(classOf(95, classification)).toBe(classification.breaks.length - 1);
  });

  it('no inventa tramos vacíos cuando el dato se repite', () => {
    const classification = classify([5, 5, 5, 5, 7], 'quantiles', 5);

    expect(classification.counts.every((count) => count > 0)).toBe(true);
  });

  it('con todos los valores iguales hay una clase, no cinco', () => {
    const classification = classify([4, 4, 4], 'equal', 5);

    expect(classification.breaks).toEqual([4]);
    expect(classification.counts).toEqual([3]);
  });

  it('sin valores no clasifica nada en vez de fallar', () => {
    expect(classify([], 'quantiles', 5).breaks).toEqual([]);
  });
});
