import { ApplicationConfig } from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';

export const appConfig: ApplicationConfig = {
  providers: [provideRouter([], withHashLocation())],
};
