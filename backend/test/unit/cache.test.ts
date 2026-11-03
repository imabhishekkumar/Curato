import { describe, expect, it, vi } from 'vitest';
import { CatalogueCache, type CacheEvent } from '../../src/adapters/fakestore/cache.js';
import { AppError } from '../../src/domain/errors.js';
import type { Product } from '../../src/domain/product.js';
import { makeProduct } from '../fixtures/builders.js';

const TTL_SECONDS = 300;
const MAX_STALE_SECONDS = 600;

/** A clock the test moves by hand, so the state machine needs no real waiting. */
function controlledClock(start = 1_000_000) {
  let current = start;
  return {
    now: () => current,
    advanceSeconds: (seconds: number) => {
      current += seconds * 1000;
    },
  };
}

interface Harness {
  cache: CatalogueCache;
  clock: ReturnType<typeof controlledClock>;
  load: ReturnType<typeof vi.fn>;
  events: CacheEvent[];
}

function harness(load: () => Promise<Product[]>): Harness {
  const clock = controlledClock();
  const events: CacheEvent[] = [];
  const spy = vi.fn(load);
  const cache = new CatalogueCache({
    ttlSeconds: TTL_SECONDS,
    maxStaleSeconds: MAX_STALE_SECONDS,
    load: spy,
    now: clock.now,
    onEvent: (event) => events.push(event),
  });
  return { cache, clock, load: spy, events };
}

const ONE = [makeProduct({ id: 1 })];
const TWO = [makeProduct({ id: 2 })];

describe('CatalogueCache — the five states of SPEC §4', () => {
  it('cold cache fetches from upstream', async () => {
    const { cache, load } = harness(async () => ONE);
    await expect(cache.get()).resolves.toEqual(ONE);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('fresh: serves from cache with no upstream call', async () => {
    const { cache, clock, load } = harness(async () => ONE);
    await cache.get();
    clock.advanceSeconds(TTL_SECONDS - 1);
    await expect(cache.get()).resolves.toEqual(ONE);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('expired, refresh succeeds: refreshes then serves the new data', async () => {
    let payload = ONE;
    const { cache, clock, load } = harness(async () => payload);
    await cache.get();
    payload = TWO;
    clock.advanceSeconds(TTL_SECONDS + 1);
    await expect(cache.get()).resolves.toEqual(TWO);
    expect(load).toHaveBeenCalledTimes(2);
  });

  it('expired, refresh fails, data held: serves stale and reports it', async () => {
    let fail = false;
    const { cache, clock, events } = harness(async () => {
      if (fail) throw new Error('upstream down');
      return ONE;
    });
    await cache.get();
    fail = true;
    clock.advanceSeconds(TTL_SECONDS + 10);
    await expect(cache.get()).resolves.toEqual(ONE);
    expect(events.some((e) => e.type === 'stale-served')).toBe(true);
  });

  it('serves stale right up to the max-stale bound', async () => {
    let fail = false;
    const { cache, clock } = harness(async () => {
      if (fail) throw new Error('upstream down');
      return ONE;
    });
    await cache.get();
    fail = true;
    clock.advanceSeconds(TTL_SECONDS + MAX_STALE_SECONDS);
    await expect(cache.get()).resolves.toEqual(ONE);
  });

  it('cold and refresh fails: UPSTREAM_UNAVAILABLE', async () => {
    const { cache } = harness(async () => {
      throw new Error('upstream down');
    });
    await expect(cache.get()).rejects.toMatchObject({ code: 'UPSTREAM_UNAVAILABLE' });
  });

  it('stale beyond the bound and refresh fails: UPSTREAM_UNAVAILABLE', async () => {
    let fail = false;
    const { cache, clock } = harness(async () => {
      if (fail) throw new Error('upstream down');
      return ONE;
    });
    await cache.get();
    fail = true;
    clock.advanceSeconds(TTL_SECONDS + MAX_STALE_SECONDS + 1);
    await expect(cache.get()).rejects.toMatchObject({ code: 'UPSTREAM_UNAVAILABLE' });
  });

  it('preserves an AppError raised by the loader rather than wrapping it twice', async () => {
    const { cache } = harness(async () => {
      throw new AppError('UPSTREAM_UNAVAILABLE', { developerMessage: 'upstream timed out' });
    });
    await expect(cache.get()).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
      developerMessage: 'upstream timed out',
    });
  });
});

describe('CatalogueCache — single-flight', () => {
  it('collapses concurrent cold reads into one upstream call', async () => {
    // Home fires /home and /categories in parallel; a cold cache must not fetch twice.
    let release: (products: Product[]) => void = () => {};
    const pending = new Promise<Product[]>((resolve) => {
      release = resolve;
    });
    const { cache, load } = harness(() => pending);

    const both = Promise.all([cache.get(), cache.get(), cache.get()]);
    release(ONE);
    await expect(both).resolves.toEqual([ONE, ONE, ONE]);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it('does not wedge after a failed refresh — the next read tries again', async () => {
    let attempt = 0;
    const { cache } = harness(async () => {
      attempt += 1;
      if (attempt === 1) throw new Error('transient');
      return ONE;
    });
    await expect(cache.get()).rejects.toMatchObject({ code: 'UPSTREAM_UNAVAILABLE' });
    await expect(cache.get()).resolves.toEqual(ONE);
  });

  it('reports warmth, which the health probe reads', async () => {
    const { cache } = harness(async () => ONE);
    expect(cache.isWarm).toBe(false);
    await cache.get();
    expect(cache.isWarm).toBe(true);
  });
});
