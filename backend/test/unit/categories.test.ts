import { describe, expect, it } from 'vitest';
import {
  CATEGORIES,
  allCategoryRefs,
  categoryBySlug,
  categoryByUpstreamName,
} from '../../src/domain/categories.js';
import { CATALOGUE } from '../fixtures/catalogue.js';

describe('the slug map (SPEC §3)', () => {
  it('maps every upstream name the live catalogue uses', () => {
    const upstreamNames = new Set(
      (CATALOGUE.length > 0 ? CATEGORIES : []).map((c) => c.upstreamName),
    );
    expect(upstreamNames).toEqual(
      new Set(['electronics', 'jewelery', "men's clothing", "women's clothing"]),
    );
  });

  it('produces URL-safe slugs with no spaces or apostrophes', () => {
    for (const { slug } of CATEGORIES) {
      expect(slug).toMatch(/^[a-z0-9-]+$/);
    }
  });

  it('keeps the upstream spelling of jewelery rather than correcting it', () => {
    expect(categoryBySlug('jewelery')).toEqual({ slug: 'jewelery', name: 'Jewelery' });
    expect(categoryBySlug('jewellery')).toBeUndefined();
  });

  it('resolves an upstream name to a ref', () => {
    expect(categoryByUpstreamName("men's clothing")).toEqual({
      slug: 'mens-clothing',
      name: "Men's Clothing",
    });
  });

  it('tolerates upstream casing and padding', () => {
    expect(categoryByUpstreamName("  MEN'S CLOTHING ")?.slug).toBe('mens-clothing');
  });

  it('returns undefined for a category it does not know', () => {
    expect(categoryByUpstreamName('groceries')).toBeUndefined();
    expect(categoryBySlug('groceries')).toBeUndefined();
  });

  it('never exposes the upstream spelling on a public ref', () => {
    for (const ref of allCategoryRefs()) {
      expect(Object.keys(ref).sort()).toEqual(['name', 'slug']);
    }
  });

  it('returns categories in a fixed order, which Home renders as shelves', () => {
    expect(allCategoryRefs().map((c) => c.slug)).toEqual([
      'electronics',
      'jewelery',
      'mens-clothing',
      'womens-clothing',
    ]);
  });
});
