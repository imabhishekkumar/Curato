/**
 * The home shelf's selection (SPEC.md §4.1).
 *
 * The brief describes the five featured products as ones that have been *chosen*, which
 * reads as curation; the obvious implementation computes them from ratings. Rather than
 * pick one reading, `FEATURED_PRODUCT_IDS` satisfies both — and the response reports
 * which one produced it, so the client can head the shelf truthfully.
 *
 * Nothing here logs or throws. Warnings are returned so the functions stay pure and the
 * exact wording is testable; the caller decides where they go.
 */
import type { Product } from './product.js';
import { SHELF_SIZE, topRated } from './ranking.js';

/**
 * Which rule produced the list.
 *
 * The client renders "Top rated" for `computed` and "Featured" for `curated`, branching
 * on this alone. A curated list is not a ranking — `FEATURED_PRODUCT_IDS=8,4` resolves
 * to the two *lowest*-rated products in the catalogue — so a shelf headed "Top rated"
 * containing one would be a lie on the first screen anyone sees. The backend never sends
 * display copy, only the discriminator.
 */
export type FeaturedSource = 'computed' | 'curated';

export interface ParsedFeaturedIds {
  ids: number[];
  warnings: string[];
}

const POSITIVE_INTEGER = /^\d+$/;

/**
 * Steps 1–3 of the normalization: parse and trim, de-duplicate, cap at `SHELF_SIZE`.
 *
 * Runs once at boot. A malformed value is a warning and never an error — the home page
 * is the first thing anyone sees, and it must not be takeable down by a typo in an env
 * var. An unset or blank value yields no ids, which means "compute".
 */
export function parseFeaturedIds(raw: string | undefined): ParsedFeaturedIds {
  const warnings: string[] = [];
  if (raw === undefined || raw.trim() === '') return { ids: [], warnings };

  const accepted: number[] = [];
  const seen = new Set<number>();

  for (const entry of raw.split(',')) {
    const token = entry.trim();
    if (token === '') continue;
    if (!POSITIVE_INTEGER.test(token)) {
      warnings.push(`FEATURED_PRODUCT_IDS: ignoring "${token}" — not a positive integer`);
      continue;
    }
    const id = Number(token);
    if (!Number.isSafeInteger(id) || id <= 0) {
      warnings.push(`FEATURED_PRODUCT_IDS: ignoring "${token}" — not a positive integer`);
      continue;
    }
    if (seen.has(id)) {
      warnings.push(`FEATURED_PRODUCT_IDS: ignoring repeated id ${id}`);
      continue;
    }
    seen.add(id);
    accepted.push(id);
  }

  if (accepted.length > SHELF_SIZE) {
    warnings.push(
      `FEATURED_PRODUCT_IDS: ${accepted.length} ids configured, using the first ${SHELF_SIZE}`,
    );
  }

  return { ids: accepted.slice(0, SHELF_SIZE), warnings };
}

export interface FeaturedSelection {
  source: FeaturedSource;
  products: Product[];
  warnings: string[];
}

/**
 * Steps 4–6: resolve the configured ids, or compute.
 *
 * **The curated list is authoritative — it is never backfilled.** If three of five ids
 * resolve, Home shows three. Topping it up with highest-rated products the curator did
 * not choose would silently override the choice the config exists to express.
 *
 * The one exception is a list where *nothing* resolves: that falls back to the computed
 * top 5 and reports `computed`, because the response describes what was actually
 * produced rather than what was configured. Home is never empty and never 5xx.
 */
export function resolveFeatured(
  products: readonly Product[],
  configuredIds: readonly number[],
): FeaturedSelection {
  if (configuredIds.length === 0) {
    return { source: 'computed', products: topRated(products), warnings: [] };
  }

  const byId = new Map(products.map((p) => [p.id, p]));
  const warnings: string[] = [];
  const resolved: Product[] = [];

  for (const id of configuredIds) {
    const product = byId.get(id);
    if (product === undefined) {
      warnings.push(`FEATURED_PRODUCT_IDS: id ${id} does not resolve to a product — skipped`);
      continue;
    }
    resolved.push(product);
  }

  if (resolved.length === 0) {
    warnings.push(
      'FEATURED_PRODUCT_IDS: no configured id resolved — falling back to the computed top rated',
    );
    return { source: 'computed', products: topRated(products), warnings };
  }

  return { source: 'curated', products: resolved, warnings };
}
