/**
 * The error-code registry (SPEC.md §5.1).
 *
 * Every failure in the system becomes an `AppError`, and every `AppError` becomes an
 * envelope. The client branches on `code` alone and never on message text, so the code
 * is the contract and the strings are free to change without a client release.
 */

export const ERROR_CODES = [
  'VALIDATION_FAILED',
  'NOT_FOUND',
  'MALFORMED_REQUEST',
  'PAYLOAD_TOO_LARGE',
  'METHOD_NOT_ALLOWED',
  'EMAIL_SEND_FAILED',
  'UPSTREAM_UNAVAILABLE',
  'RATE_LIMITED',
  'INTERNAL_ERROR',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

interface ErrorDefinition {
  httpStatus: number;
  /** The only general error string a customer is shown. */
  userMessage: string;
}

export const ERROR_REGISTRY: Readonly<Record<ErrorCode, ErrorDefinition>> = {
  VALIDATION_FAILED: {
    httpStatus: 422,
    userMessage: 'Please check the highlighted fields.',
  },
  NOT_FOUND: {
    httpStatus: 404,
    userMessage: 'We could not find what you were looking for.',
  },
  MALFORMED_REQUEST: {
    httpStatus: 400,
    userMessage: 'That request could not be read.',
  },
  PAYLOAD_TOO_LARGE: {
    httpStatus: 413,
    userMessage: 'That request was too large.',
  },
  METHOD_NOT_ALLOWED: {
    httpStatus: 405,
    userMessage: 'That action is not supported here.',
  },
  EMAIL_SEND_FAILED: {
    httpStatus: 502,
    userMessage: 'We could not send your enquiry. Please try again.',
  },
  UPSTREAM_UNAVAILABLE: {
    httpStatus: 503,
    userMessage: 'The catalogue is unavailable right now. Please try again.',
  },
  RATE_LIMITED: {
    httpStatus: 429,
    userMessage: 'Too many enquiries from this device. Please try again shortly.',
  },
  INTERNAL_ERROR: {
    httpStatus: 500,
    userMessage: 'Something went wrong on our end.',
  },
};

/** Dotted request paths (`contact.email`) mapped to the message shown under that input. */
export type FieldErrors = Record<string, string>;

export interface AppErrorOptions {
  /**
   * A short, sanitized, stable string: a *category* of failure, not a dump.
   *
   * This ships in the response body and is therefore readable by anyone who can call the
   * API. It must never carry an exception message, a stack frame, an upstream response
   * body, a hostname, a file path or a config value. Raw diagnostics are logged against
   * the `traceId` instead.
   */
  developerMessage: string;
  /** Overrides the registry default. Used where a limit or a term belongs in the copy. */
  userMessage?: string;
  /** Only for field-level failures; omitted entirely otherwise. */
  fieldErrors?: FieldErrors;
  /** Retained for the server-side log, never serialized. */
  cause?: unknown;
}

export class AppError extends Error {
  readonly code: ErrorCode;
  readonly httpStatus: number;
  readonly userMessage: string;
  readonly developerMessage: string;
  readonly fieldErrors: FieldErrors | undefined;

  constructor(code: ErrorCode, options: AppErrorOptions) {
    super(options.developerMessage, options.cause ? { cause: options.cause } : undefined);
    this.name = 'AppError';
    this.code = code;
    this.httpStatus = ERROR_REGISTRY[code].httpStatus;
    this.userMessage = options.userMessage ?? ERROR_REGISTRY[code].userMessage;
    this.developerMessage = options.developerMessage;
    this.fieldErrors = options.fieldErrors;
  }
}

export function isAppError(value: unknown): value is AppError {
  return value instanceof AppError;
}
