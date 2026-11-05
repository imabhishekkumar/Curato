/**
 * The one place upstream's refusal to use status codes can turn a missing product into
 * a 500 (SPEC.md §11.1, "Backend adapter").
 *
 * Asserted against responses recorded from the live API: `/products/999` answers `200`
 * with `content-length: 0`, and `/products/category/nonsense` answers `200 []`.
 */
import { describe, expect, it, vi } from 'vitest';
import { FakeStoreClient, parseUpstreamBody } from '../../src/adapters/fakestore/client.js';
import { mapCatalogue } from '../../src/adapters/fakestore/mapper.js';
import { CATALOGUE, UPSTREAM_CATALOGUE, idsOf } from '../fixtures/catalogue.js';

describe('parseUpstreamBody — absence is not a parse failure', () => {
  it('maps a recorded empty body to an absence rather than throwing', () => {
    // This is what `GET /products/999` actually returns: 200, content-length 0.
    expect(parseUpstreamBody('')).toBeUndefined();
  });

  it('treats a whitespace-only body as an absence', () => {
    expect(parseUpstreamBody('   \n ')).toBeUndefined();
  });

  it('never throws on an empty body — a thrown parse would surface as a 500', () => {
    expect(() => parseUpstreamBody('')).not.toThrow();
  });

  it('maps a recorded "200 []" to an empty list, which is not an absence', () => {
    // `GET /products/category/nonsense` returns an empty array, not an empty body.
    const parsed = parseUpstreamBody('[]');
    expect(parsed).toEqual([]);
    expect(parsed).not.toBeUndefined();
  });

  it('parses a real catalogue body', () => {
    const parsed = parseUpstreamBody(JSON.stringify(UPSTREAM_CATALOGUE));
    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed as unknown[]).toHaveLength(20);
  });

  it('raises UPSTREAM_UNAVAILABLE for a body that is genuinely malformed', () => {
    expect(() => parseUpstreamBody('{not json')).toThrowError(
      expect.objectContaining({ code: 'UPSTREAM_UNAVAILABLE' }),
    );
  });
});

function stubFetch(responses: Array<{ status?: number; body?: string } | Error>): typeof fetch {
  const queue = [...responses];
  return vi.fn(async () => {
    const next = queue.length > 1 ? queue.shift() : queue[0];
    if (next instanceof Error) throw next;
    return new Response(next?.body ?? '', { status: next?.status ?? 200 });
  }) as unknown as typeof fetch;
}

describe('FakeStoreClient.fetchCatalogue', () => {
  const options = { baseUrl: 'https://upstream.test', timeoutMs: 1000, retries: 0 };

  it('returns the raw upstream array', async () => {
    const client = new FakeStoreClient({
      ...options,
      fetchImpl: stubFetch([{ body: JSON.stringify(UPSTREAM_CATALOGUE) }]),
    });
    await expect(client.fetchCatalogue()).resolves.toHaveLength(20);
  });

  it('raises UPSTREAM_UNAVAILABLE when the catalogue body is empty', async () => {
    const client = new FakeStoreClient({ ...options, fetchImpl: stubFetch([{ body: '' }]) });
    await expect(client.fetchCatalogue()).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
    });
  });

  it('raises UPSTREAM_UNAVAILABLE when the catalogue is not an array', async () => {
    const client = new FakeStoreClient({ ...options, fetchImpl: stubFetch([{ body: '{}' }]) });
    await expect(client.fetchCatalogue()).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
    });
  });

  it('maps a transport failure to UPSTREAM_UNAVAILABLE, never a raw throw', async () => {
    const client = new FakeStoreClient({
      ...options,
      fetchImpl: stubFetch([new Error('ECONNREFUSED 10.0.0.4:443')]),
    });
    const error = await client.fetchCatalogue().catch((e: unknown) => e);
    expect(error).toMatchObject({ code: 'UPSTREAM_UNAVAILABLE' });
    // The connection detail must not reach the serialized field.
    expect((error as { developerMessage: string }).developerMessage).not.toContain('ECONNREFUSED');
  });

  it('retries a bounded number of times, then gives up', async () => {
    const fetchImpl = stubFetch([new Error('boom')]);
    const client = new FakeStoreClient({ ...options, retries: 2, fetchImpl });
    await expect(client.fetchCatalogue()).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it('treats a non-2xx as a fault to retry, not as a missing product', async () => {
    const fetchImpl = stubFetch([{ status: 503, body: '' }]);
    const client = new FakeStoreClient({ ...options, retries: 1, fetchImpl });
    await expect(client.fetchCatalogue()).rejects.toMatchObject({
      code: 'UPSTREAM_UNAVAILABLE',
    });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });
});

describe('mapCatalogue', () => {
  it('maps every product in the recorded catalogue', () => {
    const { products, warnings } = mapCatalogue(UPSTREAM_CATALOGUE);
    expect(products).toHaveLength(20);
    expect(warnings).toEqual([]);
  });

  it('renames image to imageUrl and attaches the currency', () => {
    const product = CATALOGUE[0]!;
    expect(product.imageUrl).toMatch(/^https?:\/\//);
    expect(product.currency).toBe('USD');
    expect(product).not.toHaveProperty('image');
  });

  it('maps every upstream category name onto a slug', () => {
    const slugs = new Set(CATALOGUE.map((p) => p.category.slug));
    expect(slugs).toEqual(new Set(['electronics', 'jewelery', 'mens-clothing', 'womens-clothing']));
  });

  it('never leaks a raw upstream category name', () => {
    for (const product of CATALOGUE) {
      expect(product.category.slug).not.toContain(' ');
      expect(product.category.slug).not.toContain("'");
    }
  });

  it('maps an empty upstream array to an empty catalogue, not an error', () => {
    expect(mapCatalogue([])).toEqual({ products: [], warnings: [] });
  });

  it('skips a malformed row and keeps the rest', () => {
    const { products, warnings } = mapCatalogue([
      UPSTREAM_CATALOGUE[0],
      { id: 'not-a-number' },
      UPSTREAM_CATALOGUE[1],
    ]);
    expect(idsOf(products)).toEqual([1, 2]);
    expect(warnings).toHaveLength(1);
    expect(warnings[0]).toContain('malformed');
  });

  it('skips a product in a category we do not know, naming it', () => {
    const { products, warnings } = mapCatalogue([
      { ...(UPSTREAM_CATALOGUE[0] as object), id: 99, category: 'groceries' },
    ]);
    expect(products).toEqual([]);
    expect(warnings[0]).toContain('groceries');
  });
});
