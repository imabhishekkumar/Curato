/**
 * The route table, and the 404/405 resolution built from it (SPEC.md §5.1).
 *
 * Express answers a known path with the wrong verb as 404, not 405. Rather than
 * hand-maintain a second list of paths to detect that, routes are registered *from* this
 * table and the terminal handler reads the same table — so the 405 layer cannot drift
 * from the routes it describes.
 */
import { pathToRegexp } from 'path-to-regexp';
import type { NextFunction, Request, Response } from 'express';
import { AppError } from '../domain/errors.js';

export type HttpMethod = 'GET' | 'POST';

export interface RouteDefinition {
  method: HttpMethod;
  path: string;
}

/**
 * Every endpoint this service answers.
 *
 * Order matters for the two that overlap: `/products/search` must be registered before
 * `/products/:id`, or the literal `search` is parsed as a product id.
 */
export const ROUTES: readonly RouteDefinition[] = [
  { method: 'GET', path: '/health' },
  { method: 'GET', path: '/home' },
  { method: 'GET', path: '/categories' },
  { method: 'GET', path: '/categories/:slug/products' },
  { method: 'GET', path: '/products/search' },
  { method: 'GET', path: '/products/:id' },
  { method: 'POST', path: '/enquiries' },
];

const COMPILED = ROUTES.map((route) => ({
  ...route,
  regexp: pathToRegexp(route.path).regexp,
}));

/** Which verbs the service answers at this path, `HEAD` included wherever `GET` is. */
export function methodsAllowedFor(pathname: string): string[] {
  const allowed = new Set<string>();
  for (const route of COMPILED) {
    if (route.regexp.test(pathname)) {
      allowed.add(route.method);
      if (route.method === 'GET') allowed.add('HEAD');
    }
  }
  return [...allowed].sort();
}

/**
 * The terminal handler: nothing matched a route, so decide *why*.
 *
 * A path we serve under a verb we do not is a different failure from a path we do not
 * serve at all, and the client treats them differently — 405 indicates a client bug,
 * 404 is a full-screen error state with a back action.
 */
export function routeFallbackHandler(req: Request, res: Response, next: NextFunction): void {
  const allowed = methodsAllowedFor(req.path);

  if (allowed.length > 0) {
    res.setHeader('Allow', allowed.join(', '));
    next(
      new AppError('METHOD_NOT_ALLOWED', {
        developerMessage: `${req.method} is not supported at this path`,
      }),
    );
    return;
  }

  next(
    new AppError('NOT_FOUND', {
      developerMessage: 'unknown route',
    }),
  );
}
