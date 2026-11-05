import { describe, expect, it } from 'vitest';
import { parseFeaturedIds, resolveFeatured } from '../../src/domain/featured.js';
import { SHELF_SIZE } from '../../src/domain/ranking.js';
import { CATALOGUE, idsOf } from '../fixtures/catalogue.js';
import { rated } from '../fixtures/builders.js';

const COMPUTED_TOP_5 = [12, 11, 3, 18, 5];

describe('parseFeaturedIds — normalization steps 1 to 3', () => {
  it('treats an unset value as no configuration', () => {
    expect(parseFeaturedIds(undefined)).toEqual({ ids: [], warnings: [] });
  });

  it('treats a blank value as no configuration, not an empty curated list', () => {
    expect(parseFeaturedIds('   ').ids).toEqual([]);
    expect(parseFeaturedIds('').ids).toEqual([]);
  });

  it('parses and trims a well-formed list, preserving order', () => {
    expect(parseFeaturedIds(' 4, 1 ,18').ids).toEqual([4, 1, 18]);
  });

  it('drops entries that are not positive integers, with a warning each', () => {
    const { ids, warnings } = parseFeaturedIds('1,abc,-3,2.5,0,4');
    expect(ids).toEqual([1, 4]);
    expect(warnings).toHaveLength(4);
    expect(warnings.join(' ')).toContain('not a positive integer');
  });

  it('de-duplicates with the first occurrence winning (AC-2c)', () => {
    const { ids, warnings } = parseFeaturedIds('4,1,4,9,1');
    expect(ids).toEqual([4, 1, 9]);
    expect(warnings.join(' ')).toContain('repeated id');
  });

  it('caps at the shelf size and says so (AC-2d)', () => {
    const { ids, warnings } = parseFeaturedIds('1,2,3,4,5,6,7');
    expect(ids).toEqual([1, 2, 3, 4, 5]);
    expect(warnings.join(' ')).toContain(`using the first ${SHELF_SIZE}`);
  });

  it('skips empty entries from trailing or doubled commas without warning', () => {
    const { ids, warnings } = parseFeaturedIds('1,,2,');
    expect(ids).toEqual([1, 2]);
    expect(warnings).toEqual([]);
  });

  it('never throws on a malformed value — a typo must not take the home page down', () => {
    expect(() => parseFeaturedIds(',,,@@@,,,')).not.toThrow();
    expect(parseFeaturedIds(',,,@@@,,,').ids).toEqual([]);
  });
});

describe('resolveFeatured — normalization steps 4 to 6', () => {
  it('computes the top five and reports "computed" when unconfigured (AC-2)', () => {
    const selection = resolveFeatured(CATALOGUE, []);
    expect(selection.source).toBe('computed');
    expect(idsOf(selection.products)).toEqual(COMPUTED_TOP_5);
  });

  it('returns every product when the catalogue holds fewer than five (AC-2)', () => {
    const tiny = [rated(1, 4, 10), rated(2, 3, 10)];
    const selection = resolveFeatured(tiny, []);
    expect(selection.source).toBe('computed');
    expect(idsOf(selection.products)).toEqual([1, 2]);
  });

  it('returns the configured ids in configured order and reports "curated" (AC-2a)', () => {
    const selection = resolveFeatured(CATALOGUE, [18, 4, 9]);
    expect(selection.source).toBe('curated');
    expect(idsOf(selection.products)).toEqual([18, 4, 9]);
  });

  it('curates products that are not top rated — a curated list is not a ranking', () => {
    // The pair named in SPEC §4.1: the two lowest-rated products in the catalogue.
    const selection = resolveFeatured(CATALOGUE, [8, 4]);
    expect(selection.source).toBe('curated');
    expect(idsOf(selection.products)).toEqual([8, 4]);
    for (const id of idsOf(selection.products)) {
      expect(COMPUTED_TOP_5).not.toContain(id);
    }
  });

  it('skips ids that do not resolve and never backfills (AC-2e)', () => {
    const selection = resolveFeatured(CATALOGUE, [1, 9999, 4]);
    expect(selection.source).toBe('curated');
    expect(idsOf(selection.products)).toEqual([1, 4]);
    expect(selection.products).toHaveLength(2);
    expect(selection.warnings.join(' ')).toContain('9999');
  });

  it('shows three when three of five resolve, rather than topping up', () => {
    const selection = resolveFeatured(CATALOGUE, [1, 9001, 4, 9002, 9]);
    expect(idsOf(selection.products)).toEqual([1, 4, 9]);
  });

  it('falls back to computed and reports it when no id resolves (AC-2f)', () => {
    const selection = resolveFeatured(CATALOGUE, [9001, 9002]);
    expect(selection.source).toBe('computed');
    expect(idsOf(selection.products)).toEqual(COMPUTED_TOP_5);
    expect(selection.warnings.join(' ')).toContain('falling back');
  });

  it('never returns an empty shelf for a non-empty catalogue (AC-2f)', () => {
    for (const configured of [[], [9999], [9999, 8888]]) {
      expect(resolveFeatured(CATALOGUE, configured).products.length).toBeGreaterThan(0);
    }
  });

  it('reports the source that was actually produced, never the one configured', () => {
    expect(resolveFeatured(CATALOGUE, [9999]).source).toBe('computed');
  });
});
