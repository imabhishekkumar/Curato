import { describe, expect, it } from 'vitest';
import { MIN_QUERY_LENGTH, searchProducts } from '../../src/domain/search.js';
import { CATALOGUE, idsOf } from '../fixtures/catalogue.js';
import { makeProduct } from '../fixtures/builders.js';

describe('searchProducts (AC-8a, AC-8b, AC-8c)', () => {
  it('matches a term in the title', () => {
    const results = searchProducts(CATALOGUE, 'backpack');
    expect(results.length).toBeGreaterThan(0);
    for (const product of results) {
      expect(product.title.toLowerCase()).toContain('backpack');
    }
  });

  it('matches a term that appears only in the description (AC-8a)', () => {
    const target = makeProduct({
      id: 501,
      title: 'Plain Title',
      description: 'Woven from reinforced sailcloth',
    });
    const results = searchProducts([target, makeProduct({ id: 502 })], 'sailcloth');
    expect(idsOf(results)).toEqual([501]);
  });

  it('excludes non-matching products (AC-8b)', () => {
    const results = searchProducts(CATALOGUE, 'backpack');
    expect(results.length).toBeLessThan(CATALOGUE.length);
  });

  it('never returns the full catalogue for a term that matches nothing', () => {
    expect(searchProducts(CATALOGUE, 'zzzzqqq')).toEqual([]);
  });

  it('matches case-insensitively (AC-8c)', () => {
    const lower = idsOf(searchProducts(CATALOGUE, 'backpack'));
    const upper = idsOf(searchProducts(CATALOGUE, 'BACKPACK'));
    const mixed = idsOf(searchProducts(CATALOGUE, 'BaCkPaCk'));
    expect(upper).toEqual(lower);
    expect(mixed).toEqual(lower);
  });

  it('trims the term before matching (AC-8c)', () => {
    expect(idsOf(searchProducts(CATALOGUE, '  backpack  '))).toEqual(
      idsOf(searchProducts(CATALOGUE, 'backpack')),
    );
  });

  it('returns nothing for a term shorter than the minimum', () => {
    expect(MIN_QUERY_LENGTH).toBe(2);
    expect(searchProducts(CATALOGUE, 'a')).toEqual([]);
    expect(searchProducts(CATALOGUE, ' a ')).toEqual([]);
    expect(searchProducts(CATALOGUE, '')).toEqual([]);
  });

  it('orders results by the standard ranking rule, so the best-rated match leads', () => {
    const results = searchProducts(CATALOGUE, 'a');
    expect(results).toEqual([]);
    const broad = searchProducts(CATALOGUE, 'the');
    const rates = broad.map((p) => p.rating.rate);
    expect([...rates]).toEqual([...rates].sort((a, b) => b - a));
  });

  it('is an empty result rather than an error when nothing matches', () => {
    expect(() => searchProducts(CATALOGUE, 'nothingmatchesthis')).not.toThrow();
  });
});
