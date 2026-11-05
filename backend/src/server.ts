/**
 * Process entry point: build the real adapters, wire them in, listen.
 *
 * Everything that touches the network or the clock is constructed here, which is why
 * `createApp` itself needs none of it to be tested.
 */
import { loadConfig } from './config/index.js';
import { FakeStoreClient } from './adapters/fakestore/client.js';
import { CatalogueCache } from './adapters/fakestore/cache.js';
import { FakeStoreProductRepository } from './adapters/fakestore/repository.js';
import { mapCatalogue } from './adapters/fakestore/mapper.js';
import { SmtpEmailService } from './adapters/email/smtp-email-service.js';
import { createApp } from './http/app.js';
import { installShutdownHandlers } from './http/shutdown.js';
import { logger } from './logger.js';

/** How long in-flight requests get to finish once a stop signal arrives. */
const SHUTDOWN_GRACE_MS = 10_000;

function main(): void {
  const config = loadConfig();

  const upstream = new FakeStoreClient({
    baseUrl: config.upstream.baseUrl,
    timeoutMs: config.upstream.timeoutMs,
    retries: config.upstream.retries,
  });

  const cache = new CatalogueCache({
    ttlSeconds: config.cache.ttlSeconds,
    maxStaleSeconds: config.cache.maxStaleSeconds,
    load: async () => {
      const raw = await upstream.fetchCatalogue();
      const { products, warnings } = mapCatalogue(raw);
      for (const warning of warnings) logger.warn(warning);
      return products;
    },
    onEvent: (event) => {
      if (event.type === 'stale-served') {
        logger.warn('serving stale catalogue — upstream refresh failed', { ageMs: event.ageMs });
      } else if (event.type === 'refreshed') {
        logger.info('catalogue refreshed', { count: event.count });
      }
    },
  });

  const emailService = new SmtpEmailService({
    host: config.smtp.host,
    port: config.smtp.port,
    from: config.mailFromAddress,
    to: config.salesInboxAddress,
  });

  const app = createApp({
    config,
    productRepository: new FakeStoreProductRepository(cache),
    emailService,
    healthProbes: {
      upstream: () => upstream.isReachable(),
      smtp: () => emailService.isReachable(),
    },
  });

  const server = app.listen(config.port, () => {
    logger.info('curato backend listening', {
      port: config.port,
      upstream: config.upstream.baseUrl,
      featured: config.featuredProductIds.length > 0 ? 'curated' : 'computed',
    });
  });

  installShutdownHandlers(server, { graceMs: SHUTDOWN_GRACE_MS });
}

try {
  main();
} catch (error) {
  // A boot failure must say why in plain text — a reviewer on a clean checkout should
  // not have to read JSON to learn that a variable is missing.
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exit(1);
}
