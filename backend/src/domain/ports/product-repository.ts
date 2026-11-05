/**
 * The product source, behind an interface (AGENTS.md "Architecture").
 *
 * One of the two boundaries the brief says will change. Swapping Fake Store for a real
 * catalogue is a new implementation of this interface and a changed binding — routes,
 * services and the client contract stay as they are.
 *
 * Deliberately narrow: today's adapter resolves everything from one cached catalogue
 * fetch, so pushing category filtering or search down into the port would invent a
 * capability no implementation has. An adapter that *can* filter upstream adds methods
 * when it arrives.
 */
import type { Product } from '../product.js';

export interface ProductRepository {
  /** The whole catalogue. Ranking needs it, so every read is derived from this. */
  getAll(): Promise<Product[]>;
  /** `undefined` is an absence, not an error — the caller turns it into `NOT_FOUND`. */
  getById(id: number): Promise<Product | undefined>;
}
