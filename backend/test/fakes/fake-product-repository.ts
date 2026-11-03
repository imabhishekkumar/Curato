/**
 * An in-memory `ProductRepository`.
 *
 * The port exists so tests need no network; this is it being used for that. Failure is
 * switchable so the error paths are reachable without breaking anything real.
 */
import { AppError } from '../../src/domain/errors.js';
import type { Product } from '../../src/domain/product.js';
import type { ProductRepository } from '../../src/domain/ports/product-repository.js';
import { CATALOGUE } from '../fixtures/catalogue.js';

export class FakeProductRepository implements ProductRepository {
  #products: Product[];
  /** When set, every read fails with it — stands in for a cold cache and a dead upstream. */
  failure: AppError | undefined;
  calls = { getAll: 0, getById: 0 };

  constructor(products: readonly Product[] = CATALOGUE) {
    this.#products = [...products];
  }

  static unavailable(): FakeProductRepository {
    const repository = new FakeProductRepository();
    repository.failure = new AppError('UPSTREAM_UNAVAILABLE', {
      developerMessage: 'upstream catalogue refresh failed with no usable cached data',
    });
    return repository;
  }

  setProducts(products: readonly Product[]): void {
    this.#products = [...products];
  }

  async getAll(): Promise<Product[]> {
    this.calls.getAll += 1;
    if (this.failure) throw this.failure;
    return [...this.#products];
  }

  async getById(id: number): Promise<Product | undefined> {
    this.calls.getById += 1;
    if (this.failure) throw this.failure;
    return this.#products.find((product) => product.id === id);
  }
}
