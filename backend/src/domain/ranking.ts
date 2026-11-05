/**
 * Ranking and the top/additional split (SPEC.md §4.1, §4.3).
 *
 * Ranking is backend-only: the API returns pre-ranked, pre-split payloads and the client
 * never sorts a list to decide what is "top rated". Upstream cannot help here even if we
 * wanted it to — its `?sort=` orders by `id` only, so every ordering in this system is
 * computed in this file.
 */
import type { Product } from './product.js';

/** How many products a shelf or a "top rated" section holds, at most. */
export const SHELF_SIZE = 5;

interface Rankable {
  id: number;
  rating: { rate: number; count: number };
}

/**
 * `rating.rate` desc → `rating.count` desc → `id` asc.
 *
 * The tie-breaks are part of the contract, not an implementation detail: "top 5 by
 * rating" is untestable when rates tie, and in the live catalogue electronics holds two
 * products at 2.9 separated only by `count` — a pair that straddles the cut at five.
 *
 * Returns a new array; the input is never mutated.
 */
export function rankProducts<T extends Rankable>(products: readonly T[]): T[] {
  return [...products].sort(
    (a, b) => b.rating.rate - a.rating.rate || b.rating.count - a.rating.count || a.id - b.id,
  );
}

/** The head of the ranking, up to `limit`. Never padded when fewer exist. */
export function topRated<T extends Rankable>(products: readonly T[], limit = SHELF_SIZE): T[] {
  return rankProducts(products).slice(0, limit);
}

export interface CategorySplit {
  topRated: Product[];
  /** The remainder after the top rated are removed, `id` ascending. Empty when none. */
  additional: Product[];
}

/**
 * Split a category into its two sections.
 *
 * The sets are disjoint and together equal the category's full contents, which is what
 * AC-6a asserts. `additional` is empty — not absent — when the category holds `limit` or
 * fewer products; the client omits the section rather than rendering it empty.
 */
export function splitCategory(products: readonly Product[], limit = SHELF_SIZE): CategorySplit {
  const ranked = rankProducts(products);
  const top = ranked.slice(0, limit);
  const topIds = new Set(top.map((p) => p.id));
  const additional = products.filter((p) => !topIds.has(p.id)).sort((a, b) => a.id - b.id);
  return { topRated: top, additional };
}
