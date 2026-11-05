/**
 * Upstream shape → our domain model (SPEC.md §3).
 *
 * Upstream data is untrusted input like any other, so it is parsed rather than cast. A
 * product that does not conform, or that sits in a category we do not know, is skipped
 * with a warning: one malformed row degrades the catalogue by one product instead of
 * failing every screen.
 */
import { z } from 'zod';
import { CURRENCY, type Product } from '../../domain/product.js';
import { categoryByUpstreamName } from '../../domain/categories.js';

const UpstreamProductSchema = z.object({
  id: z.number().int().positive(),
  title: z.string().min(1),
  price: z.number().nonnegative(),
  description: z.string(),
  category: z.string().min(1),
  image: z.string().min(1),
  rating: z.object({
    rate: z.number(),
    count: z.number().int().nonnegative(),
  }),
});

export interface MappedCatalogue {
  products: Product[];
  warnings: string[];
}

/** Map one upstream row. `undefined` means "skip this product", with the reason. */
function mapProduct(raw: unknown, index: number): { product?: Product; warning?: string } {
  const parsed = UpstreamProductSchema.safeParse(raw);
  if (!parsed.success) {
    const paths = parsed.error.issues.map((issue) => issue.path.join('.') || '(root)');
    return { warning: `upstream product at index ${index} is malformed: ${paths.join(', ')}` };
  }

  const upstream = parsed.data;
  const category = categoryByUpstreamName(upstream.category);
  if (category === undefined) {
    return {
      warning: `upstream product ${upstream.id} has unknown category "${upstream.category}" — skipped`,
    };
  }

  return {
    product: {
      id: upstream.id,
      title: upstream.title,
      price: upstream.price,
      currency: CURRENCY,
      description: upstream.description,
      category,
      imageUrl: upstream.image,
      rating: { rate: upstream.rating.rate, count: upstream.rating.count },
    },
  };
}

/**
 * Map a whole upstream catalogue.
 *
 * `200 []` maps to an empty catalogue, which is a valid answer rather than an error —
 * the caller decides whether empty is acceptable for the screen it is serving.
 */
export function mapCatalogue(raw: readonly unknown[]): MappedCatalogue {
  const products: Product[] = [];
  const warnings: string[] = [];

  raw.forEach((entry, index) => {
    const { product, warning } = mapProduct(entry, index);
    if (warning !== undefined) warnings.push(warning);
    if (product !== undefined) products.push(product);
  });

  return { products, warnings };
}
