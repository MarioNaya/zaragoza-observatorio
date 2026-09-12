import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { Column, DataTable } from './data-table';

interface Row {
  name: string;
  amount: number;
}

@Component({
  imports: [DataTable],
  template: `
    <obs-data-table
      [rows]="rows()"
      [columns]="columns"
      [total]="total()"
      [page]="page()"
      [size]="25"
      [sort]="sort()"
      (sortChanged)="sort.set($event)"
      (pageChanged)="page.set($event)"
    />
  `,
})
class Host {
  readonly rows = signal<Row[]>([
    { name: 'Zaragoza', amount: 10 },
    { name: 'Alagón', amount: 90 },
  ]);
  readonly total = signal(200);
  readonly page = signal(0);
  readonly sort = signal('name,asc');

  readonly columns: Column<Row>[] = [
    { key: 'name', label: 'Nombre', get: (row) => row.name, sortable: 'name' },
    { key: 'amount', label: 'Importe', get: (row) => String(row.amount), sortable: 'amount', numeric: true },
    { key: 'fixed', label: 'Sin orden', get: () => '—' },
  ];
}

/**
 * El contrato que la tabla no puede romper: **ordena y pagina el backend**.
 *
 * Lo que se comprueba es que pulsar una cabecera **emite** el orden en vez de tocar las filas, porque
 * reordenar en memoria ordenaría solo la página visible y eso es una mentira con forma de flecha.
 */
describe('DataTable', () => {
  function render() {
    TestBed.configureTestingModule({ imports: [Host] });
    return TestBed.createComponent(Host);
  }

  it('al pulsar una cabecera emite el orden y no toca las filas', async () => {
    const fixture = render();
    await fixture.whenStable();
    const before = [...fixture.componentInstance.rows()];

    const headers = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('thead button');
    headers[1].click();
    await fixture.whenStable();

    expect(fixture.componentInstance.sort()).toBe('amount,desc');
    expect(fixture.componentInstance.rows()).toEqual(before);
  });

  it('una columna numérica nueva empieza por lo más grande y repetir invierte', async () => {
    const fixture = render();
    await fixture.whenStable();
    const headers = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('thead button');

    headers[1].click();
    await fixture.whenStable();
    expect(fixture.componentInstance.sort()).toBe('amount,desc');

    fixture.nativeElement.querySelectorAll('thead button')[1].click();
    await fixture.whenStable();
    expect(fixture.componentInstance.sort()).toBe('amount,asc');
  });

  it('una columna sin campo de orden no es pulsable, y se ve', async () => {
    const fixture = render();
    await fixture.whenStable();

    const headers = (fixture.nativeElement as HTMLElement).querySelectorAll('thead th');
    expect(headers[2].querySelector('button')).toBeNull();
  });

  it('pagina sobre el total del servidor, no sobre las filas que tiene delante', async () => {
    const fixture = render();
    await fixture.whenStable();

    // 200 registros de 25 en 25 son 8 páginas, aunque en memoria solo haya dos filas.
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('de 8');

    const next = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.buttons button'),
    ).find((button) => button.textContent?.includes('Siguiente'))!;
    next.click();
    await fixture.whenStable();

    expect(fixture.componentInstance.page()).toBe(1);
  });

  it('sin filas lo dice en vez de enseñar una tabla vacía', async () => {
    const fixture = render();
    fixture.componentInstance.rows.set([]);
    fixture.componentInstance.total.set(0);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ningún registro casa');
  });
});
