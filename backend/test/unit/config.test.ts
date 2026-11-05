import { describe, expect, it } from 'vitest';
import { loadConfig } from '../../src/config/index.js';

/** The minimum a clean checkout must supply. */
const required = {
  SALES_INBOX_ADDRESS: 'sales@curato.test',
  MAIL_FROM_ADDRESS: 'noreply@curato.test',
};

describe('loadConfig — strict about what must exist', () => {
  it('builds a config from the required variables alone', () => {
    const config = loadConfig(required);
    expect(config.port).toBe(8080);
    expect(config.cache.ttlSeconds).toBe(300);
    expect(config.salesInboxAddress).toBe('sales@curato.test');
  });

  it('refuses to start without a sales inbox — enquiries would go nowhere', () => {
    expect(() => loadConfig({ MAIL_FROM_ADDRESS: 'a@b.co' })).toThrowError(
      /SALES_INBOX_ADDRESS/,
    );
  });

  it('refuses a malformed address rather than failing at first send', () => {
    expect(() => loadConfig({ ...required, SALES_INBOX_ADDRESS: 'not-an-email' })).toThrowError(
      /SALES_INBOX_ADDRESS/,
    );
  });

  it('reports every problem at once', () => {
    const error = (() => {
      try {
        loadConfig({});
        return undefined;
      } catch (e) {
        return e as Error;
      }
    })();
    expect(error?.message).toContain('SALES_INBOX_ADDRESS');
    expect(error?.message).toContain('MAIL_FROM_ADDRESS');
  });

  it('rejects a non-numeric port', () => {
    expect(() => loadConfig({ ...required, PORT: 'eighty-eighty' })).toThrowError(/PORT/);
  });

  it('coerces numeric strings, which is all env vars ever are', () => {
    const config = loadConfig({ ...required, PORT: '9090', CACHE_TTL_SECONDS: '60' });
    expect(config.port).toBe(9090);
    expect(config.cache.ttlSeconds).toBe(60);
  });

  it('never permits a wildcard CORS origin', () => {
    expect(() => loadConfig({ ...required, CORS_ORIGINS: '*' })).toThrowError(/CORS_ORIGINS/);
    expect(() =>
      loadConfig({ ...required, CORS_ORIGINS: 'http://localhost:8080,*' }),
    ).toThrowError(/CORS_ORIGINS/);
  });

  it('splits and trims the CORS allowlist', () => {
    const config = loadConfig({
      ...required,
      CORS_ORIGINS: ' http://a.test , http://b.test ,',
    });
    expect(config.corsOrigins).toEqual(['http://a.test', 'http://b.test']);
  });

  it('rejects an upstream base URL that is not a URL', () => {
    expect(() => loadConfig({ ...required, UPSTREAM_BASE_URL: 'fakestore' })).toThrowError(
      /UPSTREAM_BASE_URL/,
    );
  });
});

describe('loadConfig — lenient about the featured list', () => {
  it('treats an unset value as computed', () => {
    expect(loadConfig(required).featuredProductIds).toEqual([]);
  });

  it('parses, de-duplicates and caps a configured list', () => {
    const config = loadConfig({ ...required, FEATURED_PRODUCT_IDS: '4,1,4,9,2,7,11' });
    expect(config.featuredProductIds).toEqual([4, 1, 9, 2, 7]);
  });

  it('never throws on a malformed list — a typo must not stop the service booting', () => {
    expect(() => loadConfig({ ...required, FEATURED_PRODUCT_IDS: 'abc,,-1,@@' })).not.toThrow();
    expect(loadConfig({ ...required, FEATURED_PRODUCT_IDS: 'abc,,-1,@@' }).featuredProductIds).toEqual(
      [],
    );
  });

  it('treats a blank value as unset rather than an empty curated list', () => {
    expect(loadConfig({ ...required, FEATURED_PRODUCT_IDS: '' }).featuredProductIds).toEqual([]);
  });
});
