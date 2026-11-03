/**
 * `POST /enquiries` end to end against a stubbed SMTP (SPEC.md §11.1).
 *
 * The enquiry path is the only state-changing flow in the system and the only one that
 * can send real email, so it gets the only integration suite. Everything above the two
 * ports is the production code path.
 */
import { describe, expect, it } from 'vitest';
import request from 'supertest';
import type { Express } from 'express';
import { buildTestApp } from '../fakes/test-app.js';
import { FakeEmailService } from '../fakes/fake-email-service.js';
import { FakeProductRepository } from '../fakes/fake-product-repository.js';
import { CATALOGUE } from '../fixtures/catalogue.js';

const PRODUCT = CATALOGUE.find((p) => p.id === 1)!;

const validBody = {
  productId: PRODUCT.id,
  contact: { name: 'Ada Lovelace', email: 'ada@example.com', phone: '+91 9876543210' },
  message: 'Is this available in black?',
};

const post = (app: Express, body: unknown) =>
  request(app).post('/enquiries').set('content-type', 'application/json').send(body as object);

describe('a successful enquiry (AC-12)', () => {
  it('returns 200 with acceptedAt once the mail server accepts', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, validBody);

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(Date.parse(response.body.data.acceptedAt)).not.toBeNaN();
    expect(email.sent).toHaveLength(1);
  });

  it('returns no reference id, because nothing is persisted', async () => {
    const { app } = buildTestApp();
    const response = await post(app, validBody);
    expect(Object.keys(response.body.data)).toEqual(['acceptedAt']);
  });

  it('does not respond until the mail server has accepted', async () => {
    const email = new FakeEmailService();
    let release: () => void = () => {};
    email.gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const { app } = buildTestApp({ email });

    let settled = false;
    const pending = post(app, validBody).then((response) => {
      settled = true;
      return response;
    });

    // Give the request every chance to finish early. If a 2xx can be produced before
    // the send resolves, the client's "Thank you" is a claim we cannot support.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(settled).toBe(false);

    release();
    await expect(pending).resolves.toMatchObject({ status: 200 });
  });

  it('accepts an enquiry with only the required fields', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, {
      productId: PRODUCT.id,
      contact: { name: 'Ada Lovelace', email: 'ada@example.com' },
    });
    expect(response.status).toBe(200);
    expect(email.lastSent?.text).toContain('(none)');
  });
});

describe('the email carries what the backend resolved (AC-22)', () => {
  it('uses the resolved price, not a tampered seenPrice', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, { ...validBody, seenPrice: 0.01 });

    expect(response.status).toBe(200);
    const sent = email.lastSent!;
    expect(sent.text).toContain(`USD ${PRODUCT.price.toFixed(2)}`);
    // The tampered value may appear only as a parenthetical drift note, never as the
    // price of record.
    expect(sent.text).toMatch(/Price: USD \d+\.\d{2} \(customer saw USD 0\.01\)/);
  });

  it('notes drift when the seen price differs', async () => {
    const { app, email } = buildTestApp();
    await post(app, { ...validBody, seenPrice: PRODUCT.price + 10 });
    expect(email.lastSent?.text).toContain('customer saw');
  });

  it('adds no drift note when the seen price agrees', async () => {
    const { app, email } = buildTestApp();
    await post(app, { ...validBody, seenPrice: PRODUCT.price });
    expect(email.lastSent?.text).not.toContain('customer saw');
  });

  it('adds no drift note when seenPrice is absent', async () => {
    const { app, email } = buildTestApp();
    await post(app, validBody);
    expect(email.lastSent?.text).not.toContain('customer saw');
  });

  it('ignores an implausible seenPrice instead of failing the enquiry', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, { ...validBody, seenPrice: -99 });
    expect(response.status).toBe(200);
    expect(email.lastSent?.text).not.toContain('customer saw');
  });

  it('takes the title and category from the catalogue, never from the request', async () => {
    const { app, email } = buildTestApp();
    await post(app, { ...validBody, title: 'Free Yacht', category: 'boats' });
    const sent = email.lastSent!;
    expect(sent.text).toContain(PRODUCT.title);
    expect(sent.text).toContain(PRODUCT.category.name);
    expect(sent.text).not.toContain('Free Yacht');
    expect(sent.subject).toContain(PRODUCT.title);
  });

  it('sets reply-to to the customer so sales can answer them', async () => {
    const { app, email } = buildTestApp();
    await post(app, validBody);
    expect(email.lastSent?.replyTo).toBe('ada@example.com');
  });

  it('escapes customer input in the HTML body', async () => {
    const { app, email } = buildTestApp();
    await post(app, {
      ...validBody,
      contact: { ...validBody.contact, name: '<script>alert(1)</script>' },
      message: 'price < 10 && "cheap"',
    });
    const sent = email.lastSent!;
    expect(sent.html).not.toContain('<script>');
    expect(sent.html).toContain('&lt;script&gt;');
    expect(sent.html).toContain('&amp;&amp;');
  });
});

describe('a product that does not resolve (AC-19)', () => {
  it('returns NOT_FOUND with no fieldErrors', async () => {
    const { app } = buildTestApp();
    const response = await post(app, { ...validBody, productId: 9999 });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
    // Nothing the customer can retype fixes a removed product, so an inline field error
    // would offer a fix that does not exist.
    expect(response.body.error).not.toHaveProperty('fieldErrors');
  });

  it('sends no email — the product is resolved before anything goes out', async () => {
    const { app, email } = buildTestApp();
    await post(app, { ...validBody, productId: 9999 });
    expect(email.sent).toEqual([]);
  });

  it('distinguishes a malformed id from one that does not resolve', async () => {
    const { app } = buildTestApp();

    const malformed = await post(app, { ...validBody, productId: 0 });
    expect(malformed.status).toBe(422);
    expect(malformed.body.error.code).toBe('VALIDATION_FAILED');
    expect(malformed.body.error.fieldErrors).toEqual({ productId: 'Select a product' });

    const unresolvable = await post(app, { ...validBody, productId: 9999 });
    expect(unresolvable.status).toBe(404);
    expect(unresolvable.body.error).not.toHaveProperty('fieldErrors');
  });
});

describe('validation failures (AC-14)', () => {
  it('returns every field error keyed by dotted request path', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, {
      productId: PRODUCT.id,
      contact: { name: '', email: 'nope', phone: 'abc' },
      message: 'x'.repeat(1001),
    });

    expect(response.status).toBe(422);
    expect(response.body.error.code).toBe('VALIDATION_FAILED');
    expect(response.body.error.fieldErrors).toEqual({
      'contact.name': 'Enter your name',
      'contact.email': 'Enter a valid email address',
      'contact.phone': 'Enter a valid phone number',
      message: 'Message is too long',
    });
    expect(email.sent).toEqual([]);
  });

  it('validates server-side even when the client prefilled the values', async () => {
    const { app } = buildTestApp();
    const response = await post(app, {
      productId: PRODUCT.id,
      contact: { name: 'A', email: 'saved-but-invalid@' },
    });
    expect(response.status).toBe(422);
  });

  it('rejects an email containing a line break, which is header injection', async () => {
    const { app, email } = buildTestApp();
    const response = await post(app, {
      ...validBody,
      contact: { ...validBody.contact, email: 'ada@example.com\r\nBcc: victim@example.com' },
    });
    expect(response.status).toBe(422);
    expect(email.sent).toEqual([]);
  });
});

describe('delivery failures', () => {
  it('returns EMAIL_SEND_FAILED when the mail server rejects', async () => {
    const { app } = buildTestApp({ email: FakeEmailService.rejecting() });
    const response = await post(app, validBody);

    expect(response.status).toBe(502);
    expect(response.body.error.code).toBe('EMAIL_SEND_FAILED');
    expect(response.body.data).toBeNull();
  });

  it('leaks no SMTP detail into the response body', async () => {
    const { app } = buildTestApp({ email: FakeEmailService.rejecting() });
    const response = await post(app, validBody);
    const serialized = JSON.stringify(response.body);
    expect(serialized).not.toMatch(/ECONNREFUSED|:\d{2,5}\b|at\s+\S+:\d+:\d+/);
  });

  it('returns UPSTREAM_UNAVAILABLE when the catalogue cannot be read', async () => {
    const { app, email } = buildTestApp({ products: FakeProductRepository.unavailable() });
    const response = await post(app, validBody);

    expect(response.status).toBe(503);
    expect(response.body.error.code).toBe('UPSTREAM_UNAVAILABLE');
    expect(email.sent).toEqual([]);
  });
});

describe('rate limiting', () => {
  const limited = () =>
    buildTestApp({ config: { enquiryRateLimit: { max: 2, windowSeconds: 600 } } as never });

  it('rejects past the limit with RATE_LIMITED', async () => {
    const { app } = limited();
    await expect(post(app, validBody)).resolves.toMatchObject({ status: 200 });
    await expect(post(app, validBody)).resolves.toMatchObject({ status: 200 });

    const third = await post(app, validBody);
    expect(third.status).toBe(429);
    expect(third.body.error.code).toBe('RATE_LIMITED');
    expect(third.body.success).toBe(false);
  });

  it('counts attempts rather than successes, so failures cannot be used to bypass it', async () => {
    const { app, email } = limited();
    // Two rejected attempts still consume the window.
    await expect(post(app, { productId: 0 })).resolves.toMatchObject({ status: 422 });
    await expect(post(app, { productId: 0 })).resolves.toMatchObject({ status: 422 });

    const third = await post(app, validBody);
    expect(third.status).toBe(429);
    expect(email.sent).toEqual([]);
  });

  it('counts a malformed Content-Type as an attempt too', async () => {
    const { app } = limited();
    await request(app).post('/enquiries').set('content-type', 'text/plain').send('x');
    await request(app).post('/enquiries').set('content-type', 'text/plain').send('x');
    await expect(post(app, validBody)).resolves.toMatchObject({ status: 429 });
  });
});
