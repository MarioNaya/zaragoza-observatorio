import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  ApiItem,
  CrossTab,
  DistrictBoundaries,
  DistrictCard,
  Denominator,
  MeasureId,
} from './types';

/** Lo que el usuario ha pedido: medidas, ventana, denominador y orden. Es el estado entero de la pantalla. */
export interface CrossTabQuery {
  measures: MeasureId[];
  from: string | null;
  to: string | null;
  denominator: Denominator;
  populationYear: number | null;
  /** `district,asc` o `<medida>,desc`. Ordena el backend, siempre (ADR-020 §12). */
  sort: string;
}

/**
 * Acceso a la API del observatorio. Es la única pieza que conoce URLs: ningún componente construye una.
 *
 * No hay caché ni reintento. Lo primero porque el backend tampoco la tiene, a propósito (ADR-019 §9), y una
 * caché aquí haría que la pantalla enseñara cifras de antes sin poder decir de cuándo; lo segundo porque un
 * error se enseña, no se disimula.
 */
@Injectable({ providedIn: 'root' })
export class Observatory {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** La matriz: 29 filas y una columna por medida pedida. */
  crossTab(query: CrossTabQuery): Observable<ApiItem<CrossTab>> {
    return this.http.get<ApiItem<CrossTab>>(`${this.base}/api/v1/territory/districts`, {
      params: this.params(query),
    });
  }

  /** La ficha de una junta: la misma fila, con la serie de padrón entera al lado. */
  district(districtId: number, query: CrossTabQuery): Observable<ApiItem<DistrictCard>> {
    return this.http.get<ApiItem<DistrictCard>>(
      `${this.base}/api/v1/territory/districts/${districtId}`,
      // La ficha no admite `sort`: es una sola fila.
      { params: this.params({ ...query, sort: '' }) },
    );
  }

  /**
   * Los 29 contornos. Se piden una vez: no cambian entre ingestas y la respuesta viene con cache de 24 h
   * (ADR-020 §4).
   */
  boundaries(): Observable<DistrictBoundaries> {
    return this.http.get<DistrictBoundaries>(`${this.base}/api/v1/geo/boundaries`);
  }

  private params(query: CrossTabQuery): HttpParams {
    let params = new HttpParams().set('measures', query.measures.join(','));
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    if (query.denominator) {
      params = params.set('denominator', query.denominator);
    }
    if (query.populationYear) {
      params = params.set('populationYear', String(query.populationYear));
    }
    if (query.sort) {
      params = params.set('sort', query.sort);
    }
    return params;
  }
}
