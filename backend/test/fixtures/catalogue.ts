/**
 * The live Fake Store catalogue, recorded once so the suite never touches the network.
 *
 * Re-record with:
 *   curl -s https://fakestoreapi.com/products | node -e '…' > upstream-catalogue.json
 *
 * Tests that assert on specific ids are asserting against this snapshot, which is the
 * point — a silent upstream change should fail a test rather than a screen.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { mapCatalogue } from '../../src/adapters/fakestore/mapper.js';
import type { Product } from '../../src/domain/product.js';

const fixturePath = fileURLToPath(new URL('./upstream-catalogue.json', import.meta.url));

/** Exactly as upstream sent it: `image` not `imageUrl`, raw category names. */
export const UPSTREAM_CATALOGUE = JSON.parse(readFileSync(fixturePath, 'utf8')) as unknown[];

/** The same catalogue mapped into our domain model. */
export const CATALOGUE: Product[] = mapCatalogue(UPSTREAM_CATALOGUE).products;

export function productsInCategory(slug: string): Product[] {
  return CATALOGUE.filter((p) => p.category.slug === slug);
}

export function idsOf(products: readonly { id: number }[]): number[] {
  return products.map((p) => p.id);
}
