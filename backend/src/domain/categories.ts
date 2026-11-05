/**
 * The slug map (SPEC.md §3) — backend-owned and the single source of truth.
 *
 * Fake Store's raw category names contain apostrophes and spaces (`men's clothing`), so
 * they can never appear in a path. Every category crossing the API boundary carries both
 * forms, and the client renders `name` rather than deriving it from `slug`.
 */
import type { CategoryRef } from './product.js';

interface CategoryDefinition extends CategoryRef {
  /** Exactly as the upstream spells it. */
  upstreamName: string;
}

/**
 * Order matters: `GET /categories` returns categories in this sequence, and Home renders
 * one shelf per entry in the order received.
 *
 * `jewelery` keeps the upstream's single `l`. We title-case it for display but do not
 * correct the spelling — re-labelling source data would make our catalogue diverge from
 * the one we wrap, for cosmetic gain.
 */
export const CATEGORIES: readonly CategoryDefinition[] = [
  { upstreamName: 'electronics', slug: 'electronics', name: 'Electronics' },
  { upstreamName: 'jewelery', slug: 'jewelery', name: 'Jewelery' },
  { upstreamName: "men's clothing", slug: 'mens-clothing', name: "Men's Clothing" },
  { upstreamName: "women's clothing", slug: 'womens-clothing', name: "Women's Clothing" },
];

const BY_UPSTREAM_NAME = new Map(CATEGORIES.map((c) => [c.upstreamName.toLowerCase(), c]));
const BY_SLUG = new Map(CATEGORIES.map((c) => [c.slug, c]));

/** The public shape, without the upstream spelling we do not expose. */
export function toCategoryRef(definition: CategoryRef): CategoryRef {
  return { slug: definition.slug, name: definition.name };
}

/** Every category we serve, in render order. */
export function allCategoryRefs(): CategoryRef[] {
  return CATEGORIES.map(toCategoryRef);
}

/**
 * Map an upstream category name onto our `CategoryRef`.
 *
 * Returns `undefined` for a name we do not know. The caller decides what that means —
 * the mapper treats it as a product to skip rather than a crash, so a category added
 * upstream degrades our catalogue instead of taking it down.
 */
export function categoryByUpstreamName(upstreamName: string): CategoryRef | undefined {
  const found = BY_UPSTREAM_NAME.get(upstreamName.trim().toLowerCase());
  return found ? toCategoryRef(found) : undefined;
}

/** Resolve a path slug. `undefined` means the slug is unknown, which is a `NOT_FOUND`. */
export function categoryBySlug(slug: string): CategoryRef | undefined {
  const found = BY_SLUG.get(slug);
  return found ? toCategoryRef(found) : undefined;
}
