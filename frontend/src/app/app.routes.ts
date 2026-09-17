import { Routes } from '@angular/router';

/**
 * Las secciones. Cada una se carga aparte, así que abrir la portada no descarga el explorador de partidas ni
 * el mapa: el primer render es lo único que pesa.
 *
 * Las rutas van en español porque son parte del texto de usuario (regla 12) y porque se comparten.
 */
export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/home').then((m) => m.HomePage),
    title: 'Observatorio de Datos Abiertos de Zaragoza',
  },
  {
    path: 'presupuesto',
    loadComponent: () => import('./pages/budget').then((m) => m.BudgetPage),
    title: 'Presupuesto de gastos · Observatorio de Zaragoza',
  },
  {
    path: 'contratacion',
    loadComponent: () => import('./pages/contracts').then((m) => m.ContractsPage),
    title: 'Contratación pública · Observatorio de Zaragoza',
  },
  {
    path: 'subvenciones',
    loadComponent: () => import('./pages/grants').then((m) => m.GrantsPage),
    title: 'Subvenciones · Observatorio de Zaragoza',
  },
  {
    path: 'quejas',
    loadComponent: () => import('./pages/citizen').then((m) => m.CitizenPage),
    title: 'Quejas y sugerencias · Observatorio de Zaragoza',
  },
  {
    path: 'actividad',
    loadComponent: () => import('./pages/urban').then((m) => m.UrbanPage),
    title: 'Actividad urbana · Observatorio de Zaragoza',
  },
  {
    path: 'territorio',
    loadComponent: () => import('./pages/territory').then((m) => m.TerritoryPage),
    title: 'Cruce territorial · Observatorio de Zaragoza',
  },
  {
    path: 'catalogo',
    loadComponent: () => import('./pages/catalog').then((m) => m.CatalogPage),
    title: 'Monitor de frescura · Observatorio de Zaragoza',
  },
  { path: '**', redirectTo: '' },
];
