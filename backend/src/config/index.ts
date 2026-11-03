/**
 * Environment configuration, validated once at boot.
 *
 * Strict by default and lenient in exactly one place. A missing `SALES_INBOX_ADDRESS`
 * means enquiries would silently go nowhere, so the process refuses to start. A
 * malformed `FEATURED_PRODUCT_IDS` is a warning, because the home page is the first
 * thing anyone sees and must not be takeable down by a typo in an env var.
 */
import { z } from 'zod';
import { parseFeaturedIds } from '../domain/featured.js';
import { logger } from '../logger.js';

const integer = (fallback: number, min = 0) =>
  z.coerce.number().int().min(min).default(fallback);

const EnvSchema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: integer(8080, 1),

  /** Never `*` — an allowlist, even in development. */
  CORS_ORIGINS: z.string().default('http://localhost:8080'),

  UPSTREAM_BASE_URL: z.url().default('https://fakestoreapi.com'),
  UPSTREAM_TIMEOUT_MS: integer(5000, 1),
  UPSTREAM_RETRIES: integer(2),

  CACHE_TTL_SECONDS: integer(300),
  CACHE_MAX_STALE_SECONDS: integer(3600),

  MAX_REQUEST_BODY_BYTES: integer(16_384, 1),

  /** Deliberately a plain string here; normalized leniently below. */
  FEATURED_PRODUCT_IDS: z.string().optional(),

  SMTP_HOST: z.string().min(1).default('localhost'),
  SMTP_PORT: integer(1025, 1),
  SALES_INBOX_ADDRESS: z.email({ message: 'must be a valid email address' }),
  MAIL_FROM_ADDRESS: z.email({ message: 'must be a valid email address' }),

  ENQUIRY_RATE_LIMIT: integer(5, 1),
  ENQUIRY_RATE_WINDOW_S: integer(600, 1),
});

export interface AppConfig {
  nodeEnv: 'development' | 'test' | 'production';
  port: number;
  corsOrigins: string[];
  upstream: { baseUrl: string; timeoutMs: number; retries: number };
  cache: { ttlSeconds: number; maxStaleSeconds: number };
  maxRequestBodyBytes: number;
  /** Already parsed, de-duplicated and capped. Empty means "compute". */
  featuredProductIds: number[];
  smtp: { host: string; port: number };
  salesInboxAddress: string;
  mailFromAddress: string;
  enquiryRateLimit: { max: number; windowSeconds: number };
}

/**
 * Build the config, or throw with every problem listed at once.
 *
 * Reporting all failures together matters: a reviewer on a clean checkout should learn
 * about three missing variables in one run, not three.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const parsed = EnvSchema.safeParse(env);
  if (!parsed.success) {
    const problems = parsed.error.issues
      .map((issue) => `  ${issue.path.join('.') || '(root)'}: ${issue.message}`)
      .join('\n');
    throw new Error(`Invalid environment configuration:\n${problems}`);
  }

  const raw = parsed.data;
  const featured = parseFeaturedIds(raw.FEATURED_PRODUCT_IDS);
  for (const warning of featured.warnings) logger.warn(warning);

  const corsOrigins = raw.CORS_ORIGINS.split(',')
    .map((origin) => origin.trim())
    .filter((origin) => origin !== '');

  if (corsOrigins.includes('*')) {
    throw new Error('Invalid environment configuration:\n  CORS_ORIGINS: "*" is not permitted');
  }

  return {
    nodeEnv: raw.NODE_ENV,
    port: raw.PORT,
    corsOrigins,
    upstream: {
      baseUrl: raw.UPSTREAM_BASE_URL,
      timeoutMs: raw.UPSTREAM_TIMEOUT_MS,
      retries: raw.UPSTREAM_RETRIES,
    },
    cache: {
      ttlSeconds: raw.CACHE_TTL_SECONDS,
      maxStaleSeconds: raw.CACHE_MAX_STALE_SECONDS,
    },
    maxRequestBodyBytes: raw.MAX_REQUEST_BODY_BYTES,
    featuredProductIds: featured.ids,
    smtp: { host: raw.SMTP_HOST, port: raw.SMTP_PORT },
    salesInboxAddress: raw.SALES_INBOX_ADDRESS,
    mailFromAddress: raw.MAIL_FROM_ADDRESS,
    enquiryRateLimit: {
      max: raw.ENQUIRY_RATE_LIMIT,
      windowSeconds: raw.ENQUIRY_RATE_WINDOW_S,
    },
  };
}
