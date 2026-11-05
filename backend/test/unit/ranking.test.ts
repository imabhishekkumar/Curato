import { describe, expect, it } from 'vitest';
import { rankProducts, splitCategory, topRated, SHELF_SIZE } from '../../src/domain/ranking.js';
import { CATALOGUE, idsOf, productsInCategory } from '../fixtures/catalogue.js';
import { makeProduct, rated } from '../fixtures/builders.js';

describe('rankProducts (AC-2b)', () => {
  it('orders by rate descending', () => {
    const ranked = rankProducts([rated(1, 2.0, 10), rated(2, 4.5, 10), rated(3, 3.1, 10)]);
    expect(idsOf(ranked)).toEqual([2, 3, 1]);
  });

  it('breaks an equal rate by count descending', () => {
    const ranked = rankProducts([rated(1, 2.9, 250), rated(2, 2.9, 470)]);
    expect(idsOf(ranked)).toEqual([2, 1]);
  });

  it('breaks an equal rate and count by id ascending', () => {
    const ranked = rankProducts([rated(9, 2.9, 250), rated(4, 2.9, 250)]);
    expect(idsOf(ranked)).toEqual([4, 9]);
  });

  it('is deterministic across input orderings', () => {
    const products = [rated(1, 2.9, 250), rated(2, 2.9, 470), rated(3, 4.1, 5)];
    const forward = idsOf(rankProducts(products));
    const reversed = idsOf(rankProducts([...products].reverse()));
    expect(forward).toEqual(reversed);
  });

  it('does not mutate its input', () => {
    const products = [rated(1, 1, 1), rated(2, 5, 1)];
    rankProducts(products);
    expect(idsOf(products)).toEqual([1, 2]);
  });

  it('ranks the live catalogue to the expected top five', () => {
    expect(idsOf(topRated(CATALOGUE))).toEqual([12, 11, 3, 18, 5]);
  });
});

describe('topRated', () => {
  it('never pads when fewer products exist than the shelf holds', () => {
    expect(topRated([rated(1, 5, 1), rated(2, 4, 1)])).toHaveLength(2);
  });

  it('caps at the shelf size', () => {
    const many = Array.from({ length: 12 }, (_, i) => rated(i + 1, 5 - i * 0.1, 10));
    expect(topRated(many)).toHaveLength(SHELF_SIZE);
  });
});

describe('splitCategory (AC-6, AC-6a, AC-6b)', () => {
  it('returns an empty additional list when the category holds five or fewer', () => {
    const products = [rated(1, 5, 1), rated(2, 4, 1), rated(3, 3, 1)];
    const split = splitCategory(products);
    expect(split.topRated).toHaveLength(3);
    expect(split.additional).toEqual([]);
  });

  it('puts five in topRated and the remainder in additional', () => {
    const electronics = productsInCategory('electronics');
    expect(electronics.length).toBeGreaterThan(SHELF_SIZE);
    const split = splitCategory(electronics);
    expect(split.topRated).toHaveLength(SHELF_SIZE);
    expect(split.additional).toHaveLength(electronics.length - SHELF_SIZE);
  });

  it('produces disjoint sets whose union is the whole category', () => {
    const electronics = productsInCategory('electronics');
    const split = splitCategory(electronics);
    const top = new Set(idsOf(split.topRated));
    const extra = new Set(idsOf(split.additional));
    for (const id of extra) expect(top.has(id)).toBe(false);
    expect(new Set([...top, ...extra])).toEqual(new Set(idsOf(electronics)));
  });

  it('orders additional by id ascending', () => {
    const products = [
      rated(9, 1, 1),
      rated(3, 1, 1),
      rated(7, 1, 1),
      ...Array.from({ length: 5 }, (_, i) => rated(100 + i, 5, 10)),
    ];
    expect(idsOf(splitCategory(products).additional)).toEqual([3, 7, 9]);
  });

  it('keeps every product within its own category (AC-6b)', () => {
    const jewelery = productsInCategory('jewelery');
    const split = splitCategory(jewelery);
    for (const product of [...split.topRated, ...split.additional]) {
      expect(product.category.slug).toBe('jewelery');
    }
  });

  it('handles an empty category without throwing', () => {
    expect(splitCategory([])).toEqual({ topRated: [], additional: [] });
  });

  it('does not mutate its input', () => {
    const products = [makeProduct({ id: 2 }), makeProduct({ id: 1 })];
    splitCategory(products);
    expect(idsOf(products)).toEqual([2, 1]);
  });
});
