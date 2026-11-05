/**
 * HTTP access to Fake Store (SPEC.md §4, "Upstream adapter contract").
 *
 * Upstream does not use HTTP status to signal absence, so this layer translates rather
 * than trusts. Verified against the live API: an unknown id answers `200` with
 * `content-length: 0`, and an unknown category answers `200 []`.
 */
import { AppError } from '../../domain/errors.js';

/**
 * Read an upstream body into JSON, treating an **empty body as an absence**.
 *
 * The emptiness check happens before the parse, and that order is the whole point:
 * `JSON.parse('')` throws, and a thrown parse surfaces as `INTERNAL_ERROR` (500) for a
 * product that merely does not exist — the wrong code, the wrong screen, and a Retry
 * offered where retrying cannot help.
 *
 * Returns `undefined` for an absent body. `200 []` parses to an empty array, which is a
 * different thing and stays distinguishable.
 */
export function parseUpstreamBody(rawBody: string): unknown {
  if (rawBody.trim() === '') return undefined;
  try {
    return JSON.parse(rawBody) as unknown;
  } catch (cause) {
    throw new AppError('UPSTREAM_UNAVAILABLE', {
      developerMessage: 'upstream returned a body that is not valid JSON',
      cause,
    });
  }
}

export interface FakeStoreClientOptions {
  baseUrl: string;
  timeoutMs: number;
  /** Additional attempts after the first. Bounded, so a hung upstream degrades us. */
  retries: number;
  /** Injected in tests. Defaults to the global. */
  fetchImpl?: typeof fetch;
}

/** Delay between attempts. Short and fixed — this is a PoC, not a resilience library. */
const RETRY_DELAY_MS = 150;

const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

export class FakeStoreClient {
  readonly #baseUrl: string;
  readonly #timeoutMs: number;
  readonly #retries: number;
  readonly #fetch: typeof fetch;

  constructor(options: FakeStoreClientOptions) {
    this.#baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.#timeoutMs = options.timeoutMs;
    this.#retries = Math.max(0, options.retries);
    this.#fetch = options.fetchImpl ?? globalThis.fetch;
  }

  /**
   * The whole catalogue. Everything we serve is derived from it, so this is the only
   * upstream call on the hot path.
   */
  async fetchCatalogue(): Promise<unknown[]> {
    const body = await this.#getWithRetry('/products');
    const parsed = parseUpstreamBody(body);
    if (parsed === undefined) {
      throw new AppError('UPSTREAM_UNAVAILABLE', {
        developerMessage: 'upstream returned an empty catalogue body',
      });
    }
    if (!Array.isArray(parsed)) {
      throw new AppError('UPSTREAM_UNAVAILABLE', {
        developerMessage: 'upstream catalogue was not a JSON array',
      });
    }
    return parsed;
  }

  /** A cheap reachability probe for `GET /health`. Never throws. */
  async isReachable(): Promise<boolean> {
    try {
      const response = await this.#fetch(`${this.#baseUrl}/products/1`, {
        signal: AbortSignal.timeout(this.#timeoutMs),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  async #getWithRetry(path: string): Promise<string> {
    let lastCause: unknown;
    for (let attempt = 0; attempt <= this.#retries; attempt += 1) {
      try {
        return await this.#get(path);
      } catch (cause) {
        lastCause = cause;
        if (attempt < this.#retries) await sleep(RETRY_DELAY_MS);
      }
    }
    throw new AppError('UPSTREAM_UNAVAILABLE', {
      developerMessage: 'upstream product fetch failed after retries',
      cause: lastCause,
    });
  }

  async #get(path: string): Promise<string> {
    const response = await this.#fetch(`${this.#baseUrl}${path}`, {
      signal: AbortSignal.timeout(this.#timeoutMs),
      headers: { accept: 'application/json' },
    });
    if (!response.ok) {
      // Status is retried rather than mapped: upstream signals absence with a 200 and an
      // empty body, so a non-2xx here is a genuine upstream fault, never a missing row.
      throw new Error(`upstream responded ${response.status}`);
    }
    return await response.text();
  }
}
