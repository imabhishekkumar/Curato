/**
 * `POST /enquiries` (SPEC.md §4.6).
 *
 * The one genuinely abusable surface in the system: unauthenticated, and it causes email
 * to be sent. Rate limiting counts **attempts, not successes**, so repeated validation
 * failures cannot be used to get around it — which is why the limiter sits in front of
 * both the body guard and validation rather than behind them.
 */
import express, { Router, type NextFunction, type Request, type Response } from 'express';
import rateLimit from 'express-rate-limit';
import type { AppConfig } from '../../config/index.js';
import type { EnquiryService } from '../../services/enquiry-service.js';
import { AppError } from '../../domain/errors.js';
import { successEnvelope } from '../envelope.js';
import { EnquiryRequestSchema, parseOrThrow } from '../validation.js';

/**
 * Reject a non-JSON body before anything tries to interpret it.
 *
 * `express.json()` simply does not parse a body whose `Content-Type` it does not
 * recognise, leaving `req.body` empty. Without this guard a `text/plain` POST would
 * reach validation and come back as "Select a product" — a field error for a request
 * that was never readable, and a misleading one.
 */
function requireJsonContentType(req: Request, _res: Response, next: NextFunction): void {
  if (req.is('application/json') !== 'application/json') {
    next(
      new AppError('MALFORMED_REQUEST', {
        developerMessage: 'Content-Type must be application/json',
      }),
    );
    return;
  }
  next();
}

export function enquiryRouter(enquiries: EnquiryService, config: AppConfig): Router {
  const router = Router();

  const limiter = rateLimit({
    windowMs: config.enquiryRateLimit.windowSeconds * 1000,
    limit: config.enquiryRateLimit.max,
    standardHeaders: 'draft-7',
    legacyHeaders: false,
    // Routed through the catch-all so a rate-limited caller still receives the envelope.
    handler: (_req, _res, next) => {
      next(
        new AppError('RATE_LIMITED', {
          developerMessage: 'per-IP enquiry rate limit exceeded',
        }),
      );
    },
  });

  router.post(
    '/enquiries',
    limiter,
    requireJsonContentType,
    express.json({ limit: config.maxRequestBodyBytes }),
    async (req, res) => {
      const request = parseOrThrow(EnquiryRequestSchema, req.body, 'enquiry');
      // 200, not 202: the mail server has already accepted the message by the time this
      // resolves. 202 would promise later processing we do not do.
      res.status(200).json(successEnvelope(await enquiries.submit(request)));
    },
  );

  return router;
}
