import { describe, expect, it } from 'vitest';
import {
  ERROR_CODES,
  ERROR_REGISTRY,
  AppError,
  isAppError,
} from '../../src/domain/errors.js';
import { errorEnvelope, newTraceId, successEnvelope } from '../../src/http/envelope.js';

describe('the error registry (SPEC §5.1)', () => {
  it('maps every code to the status the spec states', () => {
    const expected: Record<string, number> = {
      VALIDATION_FAILED: 422,
      NOT_FOUND: 404,
      MALFORMED_REQUEST: 400,
      PAYLOAD_TOO_LARGE: 413,
      METHOD_NOT_ALLOWED: 405,
      EMAIL_SEND_FAILED: 502,
      UPSTREAM_UNAVAILABLE: 503,
      RATE_LIMITED: 429,
      INTERNAL_ERROR: 500,
    };
    for (const code of ERROR_CODES) {
      expect(ERROR_REGISTRY[code].httpStatus, code).toBe(expected[code]);
    }
  });

  it('gives every code a non-empty customer-facing message', () => {
    for (const code of ERROR_CODES) {
      expect(ERROR_REGISTRY[code].userMessage.length, code).toBeGreaterThan(0);
    }
  });

  it('registers exactly the nine codes the spec defines', () => {
    expect(ERROR_CODES).toHaveLength(9);
  });
});

describe('AppError', () => {
  it('takes its status and default copy from the registry', () => {
    const error = new AppError('NOT_FOUND', { developerMessage: 'unknown product id' });
    expect(error.httpStatus).toBe(404);
    expect(error.userMessage).toBe(ERROR_REGISTRY.NOT_FOUND.userMessage);
    expect(isAppError(error)).toBe(true);
  });

  it('allows the copy to be overridden where a limit belongs in it', () => {
    const error = new AppError('PAYLOAD_TOO_LARGE', {
      developerMessage: 'too large',
      userMessage: 'The limit is 256 bytes.',
    });
    expect(error.userMessage).toBe('The limit is 256 bytes.');
  });

  it('keeps the cause for the log without exposing it', () => {
    const cause = new Error('ECONNREFUSED 10.0.0.4:587');
    const error = new AppError('EMAIL_SEND_FAILED', {
      developerMessage: 'the mail server did not accept the message',
      cause,
    });
    expect(error.cause).toBe(cause);
    expect(error.developerMessage).not.toContain('ECONNREFUSED');
  });

  it('is not an AppError when it is some other throw', () => {
    expect(isAppError(new Error('boom'))).toBe(false);
    expect(isAppError('boom')).toBe(false);
    expect(isAppError(undefined)).toBe(false);
  });
});

describe('envelope serialization (SPEC §5)', () => {
  it('puts data and a null error on success', () => {
    expect(successEnvelope({ id: 1 })).toEqual({ success: true, data: { id: 1 }, error: null });
  });

  it('puts a null data and the error on failure', () => {
    const envelope = errorEnvelope(
      new AppError('NOT_FOUND', { developerMessage: 'unknown product id' }),
      'TRACE123',
    );
    expect(envelope.success).toBe(false);
    expect(envelope.data).toBeNull();
    expect(envelope.error.code).toBe('NOT_FOUND');
    expect(envelope.error.traceId).toBe('TRACE123');
  });

  it('omits fieldErrors entirely when the failure is not field-level', () => {
    const envelope = errorEnvelope(
      new AppError('NOT_FOUND', { developerMessage: 'unknown product id' }),
      'T',
    );
    expect('fieldErrors' in envelope.error).toBe(false);
  });

  it('includes fieldErrors when they exist', () => {
    const envelope = errorEnvelope(
      new AppError('VALIDATION_FAILED', {
        developerMessage: 'enquiry failed validation: contact.email',
        fieldErrors: { 'contact.email': 'Enter a valid email address' },
      }),
      'T',
    );
    expect(envelope.error.fieldErrors).toEqual({
      'contact.email': 'Enter a valid email address',
    });
  });

  it('keeps data and error mutually exclusive in both directions', () => {
    const ok = successEnvelope(null);
    expect(ok.error).toBeNull();
    const bad = errorEnvelope(new AppError('INTERNAL_ERROR', { developerMessage: 'x' }), 'T');
    expect(bad.data).toBeNull();
  });
});

describe('newTraceId', () => {
  it('is ten Crockford base32 characters', () => {
    expect(newTraceId()).toMatch(/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{10}$/);
  });

  it('excludes the letters Crockford drops, so it survives being retyped', () => {
    const ids = Array.from({ length: 200 }, newTraceId).join('');
    expect(ids).not.toMatch(/[ILOU]/);
  });

  it('does not repeat across many draws', () => {
    const ids = new Set(Array.from({ length: 500 }, newTraceId));
    expect(ids.size).toBe(500);
  });
});
