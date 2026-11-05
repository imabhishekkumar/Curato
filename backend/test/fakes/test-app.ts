/**
 * Build the real app over fake adapters.
 *
 * Everything above the two ports is the production code path — routing, the envelope,
 * validation, the catch-all — so these tests exercise the service rather than a
 * rehearsal of it.
 */
import type { Express } from 'express';
import type { AppConfig } from '../../src/config/index.js';
import { createApp } from '../../src/http/app.js';
import { FakeEmailService } from './fake-email-service.js';
import { FakeProductRepository } from './fake-product-repository.js';

export const TEST_CONFIG: AppConfig = {
  nodeEnv: 'test',
  port: 0,
  corsOrigins: ['http://localhost:8080'],
  upstream: { baseUrl: 'https://upstream.test', timeoutMs: 1000, retries: 0 },
  cache: { ttlSeconds: 300, maxStaleSeconds: 3600 },
  maxRequestBodyBytes: 16_384,
  featuredProductIds: [],
  smtp: { host: 'localhost', port: 1025 },
  salesInboxAddress: 'sales@curato.test',
  mailFromAddress: 'noreply@curato.test',
  // High enough that ordinary tests never trip it; the rate-limit test overrides it.
  enquiryRateLimit: { max: 1000, windowSeconds: 600 },
};

export interface TestHarness {
  app: Express;
  products: FakeProductRepository;
  email: FakeEmailService;
  config: AppConfig;
}

export function buildTestApp(overrides: Partial<TestHarness> = {}): TestHarness {
  const products = overrides.products ?? new FakeProductRepository();
  const email = overrides.email ?? new FakeEmailService();
  const config = { ...TEST_CONFIG, ...overrides.config };

  const app = createApp({
    config,
    productRepository: products,
    emailService: email,
    healthProbes: {
      upstream: async () => products.failure === undefined,
      smtp: async () => email.failure === undefined,
    },
  });

  return { app, products, email, config };
}
