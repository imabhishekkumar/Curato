/**
 * The catch-all (SPEC.md §5).
 *
 * No response leaves the service without passing through the envelope serializer —
 * including the ones no route handler owns. Without this the universal contract is only
 * true of the paths someone remembered to write, and the client's single
 * `ApiResponse<T>` parser fails on exactly the responses it has no other way to read.
 *
 * Express 5 propagates rejected promises from async handlers here on its own, so routes
 * need no wrapper utility.
 */
import type { NextFunction, Request, Response } from 'express';
import { AppError, isAppError } from '../domain/errors.js';
import { errorEnvelope } from './envelope.js';
import { traceIdOf } from './trace.js';
import { logger } from '../logger.js';

/** The shapes body-parser raises before our code ever sees the request. */
interface BodyParserError extends Error {
  type?: string;
  status?: number;
}

function fromBodyParser(error: BodyParserError, maxBytes: number): AppError | undefined {
  switch (error.type) {
    case 'entity.too.large':
      return new AppError('PAYLOAD_TOO_LARGE', {
        developerMessage: 'request body exceeded the configured size cap',
        userMessage: `That request was too large. The limit is ${maxBytes} bytes.`,
      });
    case 'entity.parse.failed':
      return new AppError('MALFORMED_REQUEST', {
        developerMessage: 'request body is not valid JSON',
      });
    case 'encoding.unsupported':
    case 'charset.unsupported':
      return new AppError('MALFORMED_REQUEST', {
        developerMessage: 'request body used an unsupported encoding',
      });
    default:
      return undefined;
  }
}

export function createErrorHandler(maxRequestBodyBytes: number) {
  return function errorHandler(
    error: unknown,
    req: Request,
    res: Response,
    next: NextFunction,
  ): void {
    // Express's own guidance: if the response has started, the framework must finish it.
    if (res.headersSent) {
      next(error);
      return;
    }

    const traceId = traceIdOf(res);
    const appError =
      (isAppError(error) ? error : undefined) ??
      fromBodyParser(error as BodyParserError, maxRequestBodyBytes) ??
      new AppError('INTERNAL_ERROR', {
        // Deliberately generic. The real cause goes to the log against this traceId —
        // the exception message is never copied into a field that ships in the body.
        developerMessage: 'unhandled error',
        cause: error,
      });

    const logFields = {
      traceId,
      code: appError.code,
      method: req.method,
      path: req.path,
      cause: error,
    };
    if (appError.httpStatus >= 500) logger.error(appError.developerMessage, logFields);
    else logger.warn(appError.developerMessage, logFields);

    res.status(appError.httpStatus).json(errorEnvelope(appError, traceId));
  };
}
