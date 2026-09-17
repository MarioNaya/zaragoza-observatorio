import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  ApiItem,
  ApiPage,
  BudgetAggregation,
  BudgetLine,
  BudgetSummary,
  CatalogPage,
  CatalogSummary,
  CitizenAggregation,
  CitizenSummary,
  ContractingProcess,
  CrossTab,
  CrossTabQuery,
  Dataset,
  DistrictBoundaries,
  DistrictCard,
  Grant,
  GrantAggregation,
  GrantsSummary,
  Premises,
  ServiceRequest,
  SpendingAggregation,
  SpendingSummary,
  UrbanAggregation,
  UrbanSummary,
} from './types';

export type Query = Record<string, string | number | boolean | null | undefined>;

/**
 * Acceso a la API del observatorio. Es la única pieza que conoce URLs: ningún componente construye una.
 *
 * Sin caché salvo dos excepciones declaradas —los contornos y el resumen del catálogo—, porque el backend
 * tampoco la tiene a propósito (ADR-019 §9) y una caché aquí haría que la pantalla enseñara cifras de antes sin
 * poder decir de cuándo. Sin reintento: un error se enseña, no se disimula.
 */
@Injectable({ providedIn: 'root' })
export class Observatory {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  /** Los 29 contornos: no cambian entre ingestas y pesan 373 KB, así que se piden una sola vez por sesión. */
  private boundariesOnce?: Observable<DistrictBoundaries>;

  private get<T>(path: string, query: Query = {}): Observable<T> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<T>(`${this.base}/api/v1${path}`, { params });
  }

  // --- geo ------------------------------------------------------------------------------------------

  boundaries(): Observable<DistrictBoundaries> {
    this.boundariesOnce ??= this.get<DistrictBoundaries>('/geo/boundaries').pipe(
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.boundariesOnce;
  }

  // --- territory ------------------------------------------------------------------------------------

  crossTab(query: CrossTabQuery): Observable<ApiItem<CrossTab>> {
    return this.get<ApiItem<CrossTab>>('/territory/districts', {
      measures: query.measures.join(','),
      from: query.from,
      to: query.to,
      denominator: query.denominator,
      populationYear: query.populationYear,
      sort: query.sort,
    });
  }

  district(districtId: number, query: CrossTabQuery): Observable<ApiItem<DistrictCard>> {
    return this.get<ApiItem<DistrictCard>>(`/territory/districts/${districtId}`, {
      measures: query.measures.join(','),
      from: query.from,
      to: query.to,
      denominator: query.denominator,
      populationYear: query.populationYear,
    });
  }

  // --- citizen --------------------------------------------------------------------------------------

  citizenSummary(): Observable<ApiItem<CitizenSummary>> {
    return this.get<ApiItem<CitizenSummary>>('/citizen/summary');
  }

  citizenRequests(query: Query): Observable<ApiPage<ServiceRequest>> {
    return this.get<ApiPage<ServiceRequest>>('/citizen/requests', query);
  }

  citizenAggregation(by: string, query: Query = {}): Observable<ApiItem<CitizenAggregation>> {
    return this.get<ApiItem<CitizenAggregation>>('/citizen/aggregations', { by, ...query });
  }

  // --- urban ----------------------------------------------------------------------------------------

  urbanSummary(): Observable<ApiItem<UrbanSummary>> {
    return this.get<ApiItem<UrbanSummary>>('/urban/summary');
  }

  urbanPremises(query: Query): Observable<ApiPage<Premises>> {
    return this.get<ApiPage<Premises>>('/urban/premises', query);
  }

  urbanAggregation(by: string, query: Query = {}): Observable<ApiItem<UrbanAggregation>> {
    return this.get<ApiItem<UrbanAggregation>>('/urban/aggregations', { by, ...query });
  }

  // --- spending: contratación -----------------------------------------------------------------------

  spendingSummary(): Observable<ApiItem<SpendingSummary>> {
    return this.get<ApiItem<SpendingSummary>>('/spending/summary');
  }

  processes(query: Query): Observable<ApiPage<ContractingProcess>> {
    return this.get<ApiPage<ContractingProcess>>('/spending/processes', query);
  }

  spendingAggregation(by: string, query: Query = {}): Observable<ApiItem<SpendingAggregation>> {
    return this.get<ApiItem<SpendingAggregation>>('/spending/aggregations', { by, ...query });
  }

  // --- spending: presupuesto ------------------------------------------------------------------------

  budgetSummary(): Observable<ApiItem<BudgetSummary>> {
    return this.get<ApiItem<BudgetSummary>>('/spending/budget/summary');
  }

  budgetLines(query: Query): Observable<ApiPage<BudgetLine>> {
    return this.get<ApiPage<BudgetLine>>('/spending/budget/lines', query);
  }

  budgetAggregation(by: string, query: Query = {}): Observable<ApiItem<BudgetAggregation>> {
    return this.get<ApiItem<BudgetAggregation>>('/spending/budget/aggregations', { by, ...query });
  }

  // --- spending: subvenciones -----------------------------------------------------------------------

  grantsSummary(): Observable<ApiItem<GrantsSummary>> {
    return this.get<ApiItem<GrantsSummary>>('/spending/grants/summary');
  }

  grants(query: Query): Observable<ApiPage<Grant>> {
    return this.get<ApiPage<Grant>>('/spending/grants', query);
  }

  grantAggregation(by: string, query: Query = {}): Observable<ApiItem<GrantAggregation>> {
    return this.get<ApiItem<GrantAggregation>>('/spending/grants/aggregations', { by, ...query });
  }

  // --- catalog --------------------------------------------------------------------------------------

  catalogSummary(): Observable<CatalogSummary & { caveats: string[] }> {
    return this.get<CatalogSummary & { caveats: string[] }>('/catalog/summary');
  }

  datasets(query: Query): Observable<CatalogPage<Dataset>> {
    return this.get<CatalogPage<Dataset>>('/catalog/datasets', query);
  }
}
