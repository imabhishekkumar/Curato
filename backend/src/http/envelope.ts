/**
 * The response envelope (SPEC.md §5).
 *
 * Every endpoint returns this shape, success and failure alike, so the client parses one
 * generic `ApiResponse<T>` into a sealed result type rather than handling each endpoint's
 * errors separately. `data` and `error` are mutually exclusive, both always present,
 * exactly one null.
 */
import { randomBytes } from 'node:crypto';
import type { AppError, ErrorCode, FieldErrors } from '../domain/errors.js';

export interface SuccessEnvelope<T> {
  success: true;
  data: T;
  error: null;
}

export interface SerializedError {
  code: ErrorCode;
  userMessage: string;
  developerMessage: string;
  traceId: string;
  /** Present only for field-level failures. */
  fieldErrors?: FieldErrors;
}

export interface ErrorEnvelope {
  success: false;
  data: null;
  error: SerializedError;
}

export type Envelope<T> = SuccessEnvelope<T> | ErrorEnvelope;

export function successEnvelope<T>(data: T): SuccessEnvelope<T> {
  return { success: true, data, error: null };
}

export function errorEnvelope(error: AppError, traceId: string): ErrorEnvelope {
  const serialized: SerializedError = {
    code: error.code,
    userMessage: error.userMessage,
    developerMessage: error.developerMessage,
    traceId,
  };
  // Omitted rather than null, so the client's "is this field-level?" check is a
  // presence check and never a null check.
  if (error.fieldErrors !== undefined) serialized.fieldErrors = error.fieldErrors;
  return { success: false, data: null, error: serialized };
}

const CROCKFORD_BASE32 = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/**
 * A short, copyable correlation id.
 *
 * Shown to a customer only on `INTERNAL_ERROR`, where it is the one safe way to get from
 * a visible failure to the log line holding the real cause. Crockford base32 because it
 * has to survive being read aloud or retyped from a screenshot.
 */
export function newTraceId(): string {
  const bytes = randomBytes(10);
  let id = '';
  for (const byte of bytes) id += CROCKFORD_BASE32[byte % 32];
  return id;
}
