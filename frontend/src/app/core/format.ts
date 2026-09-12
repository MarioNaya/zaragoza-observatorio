/**
 * Formato de cifras y fechas, en español y en un solo sitio.
 *
 * Todas las cifras salen con separador de millar y sin decimales salvo que se pidan: en una columna de 29
 * juntas o de 20 ejercicios, la coma suelta es ruido. Los importes grandes se abrevian **solo en ejes y
 * etiquetas de gráfico**, nunca en una tabla: en la tabla el euro exacto es el dato.
 */

const INTEGER = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 0 });
const DECIMAL = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 1 });
const EURO = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  maximumFractionDigits: 0,
});
const EURO_CENTS = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const DATE = new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium' });
const DATE_TIME = new Intl.DateTimeFormat('es-ES', { dateStyle: 'medium', timeStyle: 'short' });
const MONTHS = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

export function integer(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : INTEGER.format(value);
}

export function decimal(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : DECIMAL.format(value);
}

export function euro(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : EURO.format(value);
}

export function euroCents(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : EURO_CENTS.format(value);
}

/** Para ejes y etiquetas de gráfico, donde no cabe el euro exacto. 1.477.859.633 → «1.478 M€». */
export function euroShort(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  const abs = Math.abs(value);
  if (abs >= 1e9) {
    return `${DECIMAL.format(value / 1e9)} mM€`;
  }
  if (abs >= 1e6) {
    return `${INTEGER.format(Math.round(value / 1e6))} M€`;
  }
  if (abs >= 1e3) {
    return `${INTEGER.format(Math.round(value / 1e3))} k€`;
  }
  return EURO.format(value);
}

export function countShort(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  const abs = Math.abs(value);
  if (abs >= 1e6) {
    return `${DECIMAL.format(value / 1e6)} M`;
  }
  if (abs >= 1e4) {
    return `${INTEGER.format(Math.round(value / 1e3))} k`;
  }
  return INTEGER.format(value);
}

export function percent(fraction: number | null | undefined, digits = 1): string {
  if (fraction === null || fraction === undefined) {
    return '—';
  }
  return `${new Intl.NumberFormat('es-ES', { maximumFractionDigits: digits }).format(fraction * 100)} %`;
}

export function date(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : DATE.format(parsed);
}

export function dateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : DATE_TIME.format(parsed);
}

/** `2013-01` → «enero de 2013». Las claves de la agregación mensual vienen así. */
export function month(key: string): string {
  const [year, monthNumber] = key.split('-');
  const index = Number(monthNumber) - 1;
  return index >= 0 && index < 12 ? `${MONTHS[index]} de ${year}` : key;
}

/** Horas a una duración legible: la mediana de respuesta llega en horas y 1.709 no dice nada. */
export function hours(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (value < 48) {
    return `${DECIMAL.format(value)} h`;
  }
  const days = value / 24;
  if (days < 60) {
    return `${DECIMAL.format(days)} días`;
  }
  return `${DECIMAL.format(days / 30.44)} meses`;
}

/** El comienzo de un día como instante UTC, que es lo que la API espera en `from`/`to`. */
export function toInstant(day: string | null): string | null {
  return day ? `${day}T00:00:00Z` : null;
}

export function toDay(instant: string | null | undefined): string {
  return instant ? instant.slice(0, 10) : '';
}
