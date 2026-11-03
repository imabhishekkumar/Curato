/**
 * The API contract's domain model (SPEC.md §3).
 *
 * These are *our* shapes, not Fake Store's. The upstream payload is mapped to them at
 * the adapter boundary and never leaks through — swapping the product source is then a
 * new adapter rather than a change that reaches the routes or the client.
 */

/** Currency of every price we serve. Upstream prices are USD; stated, never inferred. */
export const CURRENCY = 'USD' as const;

export interface CategoryRef {
  /** URL-safe. The only form that ever appears in a path. */
  slug: string;
  /** Display form, title-cased. */
  name: string;
}

export interface Rating {
  rate: number;
  count: number;
}

/** Everything a card renders. Returned by every endpoint that produces a list. */
export interface ProductSummary {
  id: number;
  title: string;
  price: number;
  currency: typeof CURRENCY;
  category: CategoryRef;
  imageUrl: string;
  rating: Rating;
}

/** `ProductSummary` plus `description`. Returned only by `GET /products/{id}`. */
export interface Product extends ProductSummary {
  description: string;
}

/**
 * Drop `description`.
 *
 * No list screen renders one, and against the live catalogue it is 60% of a list
 * payload. An endpoint whose callers render cards should not return a field no card can
 * show — see SPEC.md §3 for why this is a separate type rather than a query flag.
 */
export function toSummary(product: Product): ProductSummary {
  const { description: _description, ...summary } = product;
  return summary;
}
