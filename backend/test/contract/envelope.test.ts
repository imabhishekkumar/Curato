/**
 * The envelope contract (SPEC.md §5, §11.1).
 *
 * One suite guards the invariant every client behaviour depends on — including the
 * framework-generated responses that are the easiest place for it to break. The client
 * parses one generic `ApiResponse<T>`; a single response that escapes this shape is a
 * crash it has no other way to interpret.
 */
import { describe, expect, it } from 'vitest';
import request from 'supertest';
import { ERROR_CODES } from '../../src/domain/errors.js';
import { buildTestApp } from '../fakes/test-app.js';
import { FakeProductRepository } from '../fakes/fake-product-repository.js';

const STACK_FRAME = /\bat\s+.+:\d+:\d+|\.(ts|js):\d+/;

interface AnyEnvelope {
  success: boolean;
  data: unknown;
  error: {
    code: string;
    userMessage: string;
    developerMessage: string;
    traceId: string;
    fieldErrors?: Record<string, string>;
  } | null;
}

function expectWellFormed(body: unknown): AnyEnvelope {
  const envelope = body as AnyEnvelope;

  expect(envelope).toHaveProperty('success');
  expect(envelope).toHaveProperty('data');
  expect(envelope).toHaveProperty('error');
  expect(typeof envelope.success).toBe('boolean');

  if (envelope.success) {
    expect(envelope.error).toBeNull();
    expect(envelope.data).not.toBeNull();
  } else {
    expect(envelope.data).toBeNull();
    expect(envelope.error).not.toBeNull();
    const error = envelope.error!;
    expect(ERROR_CODES).toContain(error.code);
    expect(error.userMessage.length).toBeGreaterThan(0);
    expect(typeof error.developerMessage).toBe('string');
    expect(error.traceId.length).toBeGreaterThan(0);
    // The field ships in the body, so the guarantee has to hold here, not in the client.
    expect(error.developerMessage).not.toMatch(STACK_FRAME);
    expect(error.developerMessage).not.toContain('Error:');
  }

  return envelope;
}

const SUCCESS_PATHS = [
  '/health',
  '/home',
  '/categories',
  '/categories/electronics/products',
  '/products/search?q=bag',
  '/products/1',
];

describe('every endpoint returns a well-formed envelope', () => {
  it.each(SUCCESS_PATHS)('GET %s', async (path) => {
    const { app } = buildTestApp();
    const response = await request(app).get(path);
    expect(response.status).toBe(200);
    const envelope = expectWellFormed(response.body);
    expect(envelope.success).toBe(true);
  });

  it('POST /enquiries', async () => {
    const { app } = buildTestApp();
    const response = await request(app)
      .post('/enquiries')
      .send({ productId: 1, contact: { name: 'Ada Lovelace', email: 'ada@example.com' } });
    expect(response.status).toBe(200);
    expect(expectWellFormed(response.body).success).toBe(true);
  });
});

describe('failures are enveloped too', () => {
  it.each([
    ['unknown product', '/products/9999', 404, 'NOT_FOUND'],
    ['non-numeric product id', '/products/abc', 404, 'NOT_FOUND'],
    ['unknown category slug (AC-6c)', '/categories/groceries/products', 404, 'NOT_FOUND'],
    ['search below the minimum', '/products/search?q=a', 422, 'VALIDATION_FAILED'],
    ['search with no term', '/products/search', 422, 'VALIDATION_FAILED'],
  ])('%s', async (_name, path, status, code) => {
    const { app } = buildTestApp();
    const response = await request(app).get(path);
    expect(response.status).toBe(status);
    const envelope = expectWellFormed(response.body);
    expect(envelope.error?.code).toBe(code);
  });

  it('maps an unavailable upstream to UPSTREAM_UNAVAILABLE', async () => {
    const { app } = buildTestApp({ products: FakeProductRepository.unavailable() });
    const response = await request(app).get('/home');
    expect(response.status).toBe(503);
    expect(expectWellFormed(response.body).error?.code).toBe('UPSTREAM_UNAVAILABLE');
  });

  it('omits fieldErrors when the failure is not field-level', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/9999');
    expect(response.body.error).not.toHaveProperty('fieldErrors');
  });

  it('includes fieldErrors when the failure is field-level', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/search?q=a');
    expect(response.body.error.fieldErrors).toEqual({ q: 'Enter at least 2 characters' });
  });
});

describe('the paths no route handler owns', () => {
  it('rejects an oversized body before parsing it', async () => {
    const { app } = buildTestApp({ config: { maxRequestBodyBytes: 256 } as never });
    const response = await request(app)
      .post('/enquiries')
      .set('content-type', 'application/json')
      .send(JSON.stringify({ productId: 1, message: 'x'.repeat(5000) }));

    expect(response.status).toBe(413);
    const envelope = expectWellFormed(response.body);
    expect(envelope.error?.code).toBe('PAYLOAD_TOO_LARGE');
    // The limit belongs in the copy, so the caller learns what it exceeded.
    expect(envelope.error?.userMessage).toContain('256');
  });

  it('rejects a malformed JSON body', async () => {
    const { app } = buildTestApp();
    const response = await request(app)
      .post('/enquiries')
      .set('content-type', 'application/json')
      .send('{"productId": 1,');

    expect(response.status).toBe(400);
    expect(expectWellFormed(response.body).error?.code).toBe('MALFORMED_REQUEST');
  });

  it('rejects a body sent with the wrong Content-Type', async () => {
    const { app } = buildTestApp();
    const response = await request(app)
      .post('/enquiries')
      .set('content-type', 'text/plain')
      .send('productId=1');

    expect(response.status).toBe(400);
    const envelope = expectWellFormed(response.body);
    expect(envelope.error?.code).toBe('MALFORMED_REQUEST');
    // Specifically not a field error — the request was never readable.
    expect(envelope.error).not.toHaveProperty('fieldErrors');
  });

  it('answers an unknown route with NOT_FOUND', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/nope');
    expect(response.status).toBe(404);
    expect(expectWellFormed(response.body).error?.code).toBe('NOT_FOUND');
  });

  it('answers a known path with the wrong verb as METHOD_NOT_ALLOWED', async () => {
    const { app } = buildTestApp();
    const response = await request(app).delete('/home');
    expect(response.status).toBe(405);
    expect(expectWellFormed(response.body).error?.code).toBe('METHOD_NOT_ALLOWED');
    expect(response.headers['allow']).toContain('GET');
  });

  it.each([
    ['POST', '/home'],
    ['GET', '/enquiries'],
    ['PUT', '/products/1'],
    ['DELETE', '/categories'],
  ])('%s %s is 405, not 404', async (method, path) => {
    const { app } = buildTestApp();
    const response = await request(app)[method.toLowerCase() as 'post'](path);
    expect(response.status).toBe(405);
    expect(expectWellFormed(response.body).error?.code).toBe('METHOD_NOT_ALLOWED');
  });

  it('distinguishes a wrong verb on a parameterised path from an unknown one', async () => {
    const { app } = buildTestApp();
    await expect(request(app).post('/categories/electronics/products')).resolves.toMatchObject({
      status: 405,
    });
    await expect(request(app).post('/categories/electronics/nope')).resolves.toMatchObject({
      status: 404,
    });
  });
});

describe('list endpoints carry ProductSummary, the product endpoint carries Product', () => {
  const SUMMARY_FIELDS = ['id', 'title', 'price', 'currency', 'category', 'imageUrl', 'rating'];

  function collectProducts(body: Record<string, unknown>): Array<Record<string, unknown>> {
    const found: Array<Record<string, unknown>> = [];
    const walk = (value: unknown): void => {
      if (Array.isArray(value)) {
        value.forEach(walk);
        return;
      }
      if (value !== null && typeof value === 'object') {
        const record = value as Record<string, unknown>;
        if ('id' in record && 'title' in record && 'price' in record) found.push(record);
        else Object.values(record).forEach(walk);
      }
    };
    walk(body);
    return found;
  }

  it.each(['/home', '/categories', '/categories/electronics/products', '/products/search?q=bag'])(
    '%s returns no description',
    async (path) => {
      const { app } = buildTestApp();
      const response = await request(app).get(path);
      const products = collectProducts(response.body.data);
      expect(products.length).toBeGreaterThan(0);
      for (const product of products) {
        expect(product).not.toHaveProperty('description');
        expect(Object.keys(product).sort()).toEqual([...SUMMARY_FIELDS].sort());
      }
    },
  );

  it('GET /products/:id returns the full product including description (AC-9)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/1');
    expect(Object.keys(response.body.data).sort()).toEqual(
      [...SUMMARY_FIELDS, 'description'].sort(),
    );
    expect(typeof response.body.data.description).toBe('string');
    expect(response.body.data.description.length).toBeGreaterThan(0);
  });

  it('never exposes an upstream field name', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/1');
    expect(response.body.data).not.toHaveProperty('image');
    expect(response.body.data.category).toEqual({ slug: 'mens-clothing', name: "Men's Clothing" });
  });
});

describe('endpoint behaviour the acceptance criteria name', () => {
  it('an empty search is a success, not an error (AC-8)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/search?q=zzzzqqq');

    // The distinction is the point: no match is a populated 200 with nothing in it, so
    // the client renders an empty state naming the term rather than an error screen.
    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(response.body.error).toBeNull();
    expect(response.body.data).toEqual({ query: 'zzzzqqq', results: [] });
  });

  it('echoes the term it searched, trimmed, so the empty state can name it (AC-8)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/search?q=%20%20zzzzqqq%20%20');
    expect(response.body.data.query).toBe('zzzzqqq');
  });

  it('omits nothing but returns an empty additional for a small category (AC-6)', async () => {
    const { app } = buildTestApp();
    // Jewelery holds 4 in the recorded catalogue — fewer than a shelf.
    const response = await request(app).get('/categories/jewelery/products');

    expect(response.status).toBe(200);
    expect(response.body.data.topRated).toHaveLength(4);
    // Empty, not absent: the client omits the section, the backend does not omit the key.
    expect(response.body.data.additional).toEqual([]);
    expect(response.body.data).toHaveProperty('additional');
  });

  it('splits a category larger than a shelf into five plus the remainder (AC-6a)', async () => {
    const { app } = buildTestApp();
    // Electronics holds 6.
    const response = await request(app).get('/categories/electronics/products');
    const { topRated, additional } = response.body.data;

    expect(topRated).toHaveLength(5);
    expect(additional).toHaveLength(1);
    const ids = [...topRated, ...additional].map((p: { id: number }) => p.id);
    expect(new Set(ids).size).toBe(6);
  });

  it('names the category from the response, never from the slug (AC-6)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/categories/mens-clothing/products');
    expect(response.body.data.category).toEqual({
      slug: 'mens-clothing',
      name: "Men's Clothing",
    });
  });

  it('returns every product of the requested category and no other (AC-6b)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/categories/jewelery/products');
    for (const product of response.body.data.topRated) {
      expect(product.category.slug).toBe('jewelery');
    }
  });

  it('heads the home shelf by source, never by inspecting the products (AC-2)', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/home');
    expect(response.body.data.featured.source).toBe('computed');
    // The backend sends the discriminator and never the display copy.
    expect(JSON.stringify(response.body.data)).not.toMatch(/Top rated|Featured/);
  });

  it('reports curated when ids are configured (AC-2a)', async () => {
    const { app } = buildTestApp({ config: { featuredProductIds: [8, 4] } as never });
    const response = await request(app).get('/home');
    expect(response.body.data.featured.source).toBe('curated');
    expect(response.body.data.featured.products.map((p: { id: number }) => p.id)).toEqual([8, 4]);
  });
});

describe('transport hardening', () => {
  it('does not advertise the framework', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/health');
    expect(response.headers).not.toHaveProperty('x-powered-by');
  });

  it('sets standard security headers', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/health');
    expect(response.headers).toHaveProperty('x-content-type-options', 'nosniff');
  });

  it('echoes a trace id that matches the one in an error body', async () => {
    const { app } = buildTestApp();
    const response = await request(app).get('/products/9999');
    expect(response.headers['x-trace-id']).toBe(response.body.error.traceId);
  });
});
