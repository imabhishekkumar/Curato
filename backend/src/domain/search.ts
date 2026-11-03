/**
 * Search filtering (SPEC.md §4.4).
 */
import type { Product } from './product.js';
import { rankProducts } from './ranking.js';

/** Below this, the client disables submission and the server rejects. */
export const MIN_QUERY_LENGTH = 2;

/**
 * Case-insensitive substring match against `title` **and** `description`.
 *
 * The match reads a field the response does not return: results carry `ProductSummary`,
 * which has no `description`. A product can therefore come back for a term that appears
 * nowhere on its visible card — correct, and the reason the results screen names the
 * term it searched.
 *
 * Ordered by the standard ranking rule, so the best-rated match leads.
 */
export function searchProducts(products: readonly Product[], rawQuery: string): Product[] {
  const needle = rawQuery.trim().toLowerCase();
  if (needle.length < MIN_QUERY_LENGTH) return [];
  const matches = products.filter(
    (p) =>
      p.title.toLowerCase().includes(needle) || p.description.toLowerCase().includes(needle),
  );
  return rankProducts(matches);
}
