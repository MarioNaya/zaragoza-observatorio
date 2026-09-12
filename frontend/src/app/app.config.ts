import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // `withFetch` en vez de XHR: la API se consume desde otro dominio y esto deja el CORS en manos del
    // navegador sin capas intermedias (ADR-020 §2).
    provideHttpClient(withFetch()),
  ],
};
