/**
 * Request correlation.
 *
 * Every request gets a `traceId` before anything can fail, so the catch-all always has
 * one to report and to log against.
 */
import type { NextFunction, Request, Response } from 'express';
import { newTraceId } from './envelope.js';

const TRACE_HEADER = 'x-trace-id';

export function traceMiddleware(req: Request, res: Response, next: NextFunction): void {
  const traceId = newTraceId();
  res.locals.traceId = traceId;
  // Echoed so a reviewer watching curl output can grep the logs without reading a body.
  res.setHeader(TRACE_HEADER, traceId);
  next();
}

/**
 * The request's trace id.
 *
 * Falls back to a fresh one rather than returning empty: a response that reports no
 * trace id is worse than one reporting an id with no matching log line.
 */
export function traceIdOf(res: Response): string {
  const existing = res.locals.traceId;
  return typeof existing === 'string' ? existing : newTraceId();
}
