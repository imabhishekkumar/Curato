/**
 * The read side (SPEC.md §4.1–§4.5).
 *
 * Every ordering and every split happens here, because ranking is backend-only: the API
 * returns pre-ranked, pre-split payloads and the client never sorts a list to decide
 * what is "top rated". Upstream could not help even if we let it — its `?sort=` orders
 * by `id` alone.
 */
import { AppError } from '../domain/errors.js';
import { allCategoryRefs, categoryBySlug } from '../domain/categories.js';
import { resolveFeatured, type FeaturedSource } from '../domain/featured.js';
import { toSummary, type CategoryRef, type Product, type ProductSummary } from '../domain/product.js';
import { splitCategory, topRated } from '../domain/ranking.js';
import { searchProducts } from '../domain/search.js';
import type { ProductRepository } from '../domain/ports/product-repository.js';
import { logger } from '../logger.js';

export interface HomePayload {
  featured: { source: FeaturedSource; products: ProductSummary[] };
}

export interface CategoriesPayload {
  categories: Array<{ category: CategoryRef; topRated: ProductSummary[] }>;
}

export interface CategoryProductsPayload {
  category: CategoryRef;
  topRated: ProductSummary[];
  additional: ProductSummary[];
}

export interface SearchPayload {
  query: string;
  results: ProductSummary[];
}

export class CatalogueService {
  readonly #products: ProductRepository;
  readonly #featuredIds: readonly number[];

  constructor(products: ProductRepository, featuredIds: readonly number[]) {
    this.#products = products;
    this.#featuredIds = featuredIds;
  }

  /** The global shelf, and nothing else — the category list comes from `getCategories`. */
  async getHome(): Promise<HomePayload> {
    const all = await this.#products.getAll();
    const selection = resolveFeatured(all, this.#featuredIds);
    for (const warning of selection.warnings) logger.warn(warning);
    return {
      featured: {
        source: selection.source,
        products: selection.products.map(toSummary),
      },
    };
  }

  /**
   * Every category with the head of its ranking, in one response.
   *
   * The backend owns the order, as it owns the ranking and the slug map. Shelves are a
   * selection rather than the category: electronics and women's clothing hold six
   * products each, so one of each is reachable only through search or "View all".
   */
  async getCategories(): Promise<CategoriesPayload> {
    const all = await this.#products.getAll();
    return {
      categories: allCategoryRefs().map((category) => ({
        category,
        topRated: topRated(all.filter((p) => p.category.slug === category.slug)).map(toSummary),
      })),
    };
  }

  /** One category's complete contents. Unknown slug is an absence, not an empty page. */
  async getCategoryProducts(slug: string): Promise<CategoryProductsPayload> {
    const category = categoryBySlug(slug);
    if (category === undefined) {
      throw new AppError('NOT_FOUND', { developerMessage: 'unknown category slug' });
    }
    const all = await this.#products.getAll();
    const split = splitCategory(all.filter((p) => p.category.slug === category.slug));
    return {
      category,
      topRated: split.topRated.map(toSummary),
      additional: split.additional.map(toSummary),
    };
  }

  /**
   * Search.
   *
   * Matching reads `description`, which the response does not return — a product can
   * come back for a term that appears nowhere on its card. The results screen names the
   * term it searched for exactly this reason.
   */
  async search(query: string): Promise<SearchPayload> {
    const all = await this.#products.getAll();
    return { query, results: searchProducts(all, query).map(toSummary) };
  }

  /** The only full `Product` we serve, and the only source of `description`. */
  async getProduct(id: number): Promise<Product> {
    const product = await this.#products.getById(id);
    if (product === undefined) {
      throw new AppError('NOT_FOUND', { developerMessage: 'unknown product id' });
    }
    return product;
  }
}
