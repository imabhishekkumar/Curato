/**
 * The read endpoints (SPEC.md §4.1–§4.5).
 *
 * Registration order follows the route table, and the one ordering that matters is
 * `/products/search` before `/products/:id` — otherwise the literal `search` is parsed
 * as a product id.
 *
 * Express 5 forwards a rejected promise to the catch-all on its own, so these handlers
 * need no try/catch and no wrapper.
 */
import { Router } from 'express';
import type { CatalogueService } from '../../services/catalogue-service.js';
import { AppError } from '../../domain/errors.js';
import { successEnvelope } from '../envelope.js';
import { SearchQuerySchema, parseOrThrow } from '../validation.js';

/** Upstream answers an unknown *or* non-numeric id with an absence, and so do we. */
function parseProductId(raw: string): number {
  if (!/^\d+$/.test(raw)) {
    throw new AppError('NOT_FOUND', { developerMessage: 'product id is not a positive integer' });
  }
  const id = Number(raw);
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw new AppError('NOT_FOUND', { developerMessage: 'product id is out of range' });
  }
  return id;
}

export function catalogueRouter(catalogue: CatalogueService): Router {
  const router = Router();

  router.get('/home', async (_req, res) => {
    res.json(successEnvelope(await catalogue.getHome()));
  });

  router.get('/categories', async (_req, res) => {
    res.json(successEnvelope(await catalogue.getCategories()));
  });

  router.get('/categories/:slug/products', async (req, res) => {
    res.json(successEnvelope(await catalogue.getCategoryProducts(req.params.slug)));
  });

  router.get('/products/search', async (req, res) => {
    const { q } = parseOrThrow(SearchQuerySchema, req.query, 'search query');
    res.json(successEnvelope(await catalogue.search(q)));
  });

  router.get('/products/:id', async (req, res) => {
    const product = await catalogue.getProduct(parseProductId(req.params.id));
    res.json(successEnvelope(product));
  });

  return router;
}
