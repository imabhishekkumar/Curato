/**
 * The upstream catalogue cache (SPEC.md §4, "Upstream caching").
 *
 * Two reasons it exists: ranking needs the whole catalogue, so without it every request
 * re-fetches all products; and when upstream is slow or down, a warm cache keeps the app
 * usable instead of failing every screen.
 *
 * The consequence is stated rather than hidden — reads are eventually consistent with
 * upstream, bounded by the TTL. A product removed upstream can still be served, and
 * enquired about, for up to one TTL. Server-side resolution exists to stop a *client*
 * supplying false data, not to guarantee real-time pricing.
 */
import { AppError } from '../../domain/errors.js';
import type { Product } from '../../domain/product.js';

export type CacheEvent =
  | { type: 'refreshed'; count: number }
  | { type: 'stale-served'; ageMs: number }
  | { type: 'refresh-failed'; cause: unknown };

export interface CatalogueCacheOptions {
  ttlSeconds: number;
  /** How far **past the TTL** stale data may still be served when upstream is down. */
  maxStaleSeconds: number;
  load: () => Promise<Product[]>;
  /** Injected so the state machine is testable without waiting on real time. */
  now?: () => number;
  onEvent?: (event: CacheEvent) => void;
}

interface CacheEntry {
  products: Product[];
  fetchedAt: number;
}

export class CatalogueCache {
  readonly #ttlMs: number;
  readonly #maxStaleMs: number;
  readonly #load: () => Promise<Product[]>;
  readonly #now: () => number;
  readonly #onEvent: (event: CacheEvent) => void;

  #entry: CacheEntry | undefined;
  /**
   * The in-progress refresh, if any.
   *
   * Home fires `/home` and `/categories` in parallel. Without this, both would miss a
   * cold cache and both would hit upstream — on the first request of every session.
   */
  #inflight: Promise<Product[]> | undefined;

  constructor(options: CatalogueCacheOptions) {
    this.#ttlMs = options.ttlSeconds * 1000;
    this.#maxStaleMs = options.maxStaleSeconds * 1000;
    this.#load = options.load;
    this.#now = options.now ?? Date.now;
    this.#onEvent = options.onEvent ?? (() => {});
  }

  /** True when data is held, whatever its age. Used by the health probe. */
  get isWarm(): boolean {
    return this.#entry !== undefined;
  }

  /**
   * The catalogue, honouring the five states of the SPEC §4 table.
   *
   * Throws `UPSTREAM_UNAVAILABLE` only when there is genuinely nothing useful to serve:
   * a cold cache, or data so old it has stopped being worth serving.
   */
  async get(): Promise<Product[]> {
    const entry = this.#entry;

    // Fresh: serve from cache, no upstream call.
    if (entry !== undefined && this.#now() - entry.fetchedAt < this.#ttlMs) {
      return entry.products;
    }

    try {
      // Expired (or cold): refresh synchronously, then serve.
      return await this.#refresh();
    } catch (cause) {
      this.#onEvent({ type: 'refresh-failed', cause });

      // Refresh failed. Degrading beats a dead catalogue, within a bound.
      if (entry !== undefined) {
        const ageMs = this.#now() - entry.fetchedAt;
        if (ageMs <= this.#ttlMs + this.#maxStaleMs) {
          this.#onEvent({ type: 'stale-served', ageMs });
          return entry.products;
        }
      }

      // Cold, or stale past the bound: there is a limit to how old is still useful.
      throw cause instanceof AppError
        ? cause
        : new AppError('UPSTREAM_UNAVAILABLE', {
            developerMessage: 'upstream catalogue refresh failed with no usable cached data',
            cause,
          });
    }
  }

  #refresh(): Promise<Product[]> {
    const existing = this.#inflight;
    if (existing !== undefined) return existing;

    const attempt = (async (): Promise<Product[]> => {
      const products = await this.#load();
      this.#entry = { products, fetchedAt: this.#now() };
      this.#onEvent({ type: 'refreshed', count: products.length });
      return products;
    })();

    this.#inflight = attempt;
    // Clear the slot whatever the outcome, so a failed refresh does not wedge the cache.
    // The derived promise is caught separately; `attempt` itself is handled by the caller.
    attempt
      .finally(() => {
        if (this.#inflight === attempt) this.#inflight = undefined;
      })
      .catch(() => {});

    return attempt;
  }
}
