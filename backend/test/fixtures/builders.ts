/**
 * Synthetic products, for the rules that need controlled input.
 *
 * Tie-breaks and edge cases are easier to state with three invented products than with
 * a 20-product snapshot; the snapshot covers the realistic shape separately.
 */
import { CURRENCY, type Product } from '../../src/domain/product.js';

let nextId = 1;

export function makeProduct(overrides: Partial<Product> = {}): Product {
  const id = overrides.id ?? nextId++;
  return {
    id,
    title: `Product ${id}`,
    price: 9.99,
    currency: CURRENCY,
    description: `Description for product ${id}`,
    category: { slug: 'electronics', name: 'Electronics' },
    imageUrl: `https://example.test/${id}.png`,
    rating: { rate: 3, count: 10 },
    ...overrides,
  };
}

/** Shorthand: `rated(1, 4.5, 100)` is id 1 with rate 4.5 and count 100. */
export function rated(id: number, rate: number, count: number): Product {
  return makeProduct({ id, rating: { rate, count } });
}
