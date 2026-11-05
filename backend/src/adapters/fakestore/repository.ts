/**
 * `ProductRepository` over Fake Store (AGENTS.md "Architecture").
 *
 * Everything resolves from one cached catalogue fetch, including `getById`. That is
 * deliberate: giving single-product reads their own uncached upstream call would put a
 * network hop on the enquiry path and make the single conversion flow the most fragile
 * request in the system.
 */
import type { Product } from '../../domain/product.js';
import type { ProductRepository } from '../../domain/ports/product-repository.js';
import type { CatalogueCache } from './cache.js';

export class FakeStoreProductRepository implements ProductRepository {
  readonly #cache: CatalogueCache;

  constructor(cache: CatalogueCache) {
    this.#cache = cache;
  }

  async getAll(): Promise<Product[]> {
    return await this.#cache.get();
  }

  async getById(id: number): Promise<Product | undefined> {
    const products = await this.#cache.get();
    return products.find((product) => product.id === id);
  }
}
