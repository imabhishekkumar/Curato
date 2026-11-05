/**
 * Application assembly.
 *
 * `createApp` takes its dependencies as arguments rather than reaching for a container.
 * At this size a DI framework would be ceremony, and this shape makes every HTTP test a
 * one-liner: hand it a fake repository and a fake email service and drive it with
 * Supertest, no network and no SMTP.
 */
import express, { type Express } from 'express';
import cors from 'cors';
import helmet from 'helmet';
import type { AppConfig } from '../config/index.js';
import type { EmailService } from '../domain/ports/email-service.js';
import type { ProductRepository } from '../domain/ports/product-repository.js';
import { CatalogueService } from '../services/catalogue-service.js';
import { EnquiryService } from '../services/enquiry-service.js';
import { createErrorHandler } from './error-handler.js';
import { routeFallbackHandler } from './route-table.js';
import { traceMiddleware } from './trace.js';
import { catalogueRouter } from './routes/catalogue.js';
import { enquiryRouter } from './routes/enquiries.js';
import { healthRouter, type HealthProbes } from './routes/health.js';

export interface AppDependencies {
  config: AppConfig;
  productRepository: ProductRepository;
  emailService: EmailService;
  healthProbes: HealthProbes;
}

export function createApp(deps: AppDependencies): Express {
  const { config, productRepository, emailService, healthProbes } = deps;

  const catalogue = new CatalogueService(productRepository, config.featuredProductIds);
  const enquiries = new EnquiryService(productRepository, emailService);

  const app = express();

  // No framework fingerprint, and the standard security headers.
  app.disable('x-powered-by');
  app.use(helmet());
  app.use(
    cors({
      origin: config.corsOrigins,
      methods: ['GET', 'POST'],
    }),
  );

  // Before anything can fail, so the catch-all always has a trace id to report.
  app.use(traceMiddleware);

  // Body parsing is scoped to the one endpoint that has a body, not mounted app-wide.
  // Mounted globally it runs before routing, so a POST to a GET-only path would be
  // judged on its Content-Type and answered 400 — when the honest answer is that the
  // verb is wrong, whatever the body claims to be.
  app.use(healthRouter(healthProbes));
  app.use(catalogueRouter(catalogue));
  app.use(enquiryRouter(enquiries, config));

  // Nothing matched: decide whether that is a wrong verb or an unknown path.
  app.use(routeFallbackHandler);
  app.use(createErrorHandler(config.maxRequestBodyBytes));

  return app;
}
