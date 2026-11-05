import { describe, expect, it } from 'vitest';
import {
  EnquiryRequestSchema,
  SearchQuerySchema,
  parseOrThrow,
  toFieldErrors,
} from '../../src/http/validation.js';
import { AppError } from '../../src/domain/errors.js';

const validEnquiry = {
  productId: 1,
  contact: { name: 'Ada Lovelace', email: 'ada@example.com' },
};

function fieldErrorsFor(input: unknown): Record<string, string> {
  const result = EnquiryRequestSchema.safeParse(input);
  if (result.success) throw new Error('expected validation to fail');
  return toFieldErrors(result.error);
}

describe('enquiry validation (SPEC §6)', () => {
  it('accepts the minimum valid enquiry', () => {
    expect(EnquiryRequestSchema.safeParse(validEnquiry).success).toBe(true);
  });

  it('trims the name and rejects one shorter than two characters', () => {
    expect(fieldErrorsFor({ ...validEnquiry, contact: { name: ' A ', email: 'a@b.co' } })).toEqual(
      { 'contact.name': 'Enter your name' },
    );
  });

  it('rejects a name longer than 80 characters', () => {
    const contact = { name: 'x'.repeat(81), email: 'a@b.co' };
    expect(fieldErrorsFor({ ...validEnquiry, contact })['contact.name']).toBe('Enter your name');
  });

  it('trims surrounding whitespace from accepted values', () => {
    const parsed = EnquiryRequestSchema.parse({
      productId: 1,
      contact: { name: '  Ada Lovelace  ', email: '  ada@example.com  ' },
    });
    expect(parsed.contact.name).toBe('Ada Lovelace');
    expect(parsed.contact.email).toBe('ada@example.com');
  });

  it('rejects a malformed email with the spec message', () => {
    expect(fieldErrorsFor({ ...validEnquiry, contact: { name: 'Ada', email: 'not-an-email' } })).toEqual(
      { 'contact.email': 'Enter a valid email address' },
    );
  });

  it('accepts the phone formats named in the spec', () => {
    for (const phone of ['+91 9876543210', '(020) 7946 0991']) {
      const parsed = EnquiryRequestSchema.safeParse({
        ...validEnquiry,
        contact: { ...validEnquiry.contact, phone },
      });
      expect(parsed.success, phone).toBe(true);
    }
  });

  it('rejects a phone containing letters', () => {
    const contact = { ...validEnquiry.contact, phone: '0800 CALL NOW' };
    expect(fieldErrorsFor({ ...validEnquiry, contact })['contact.phone']).toBe(
      'Enter a valid phone number',
    );
  });

  it('treats an empty phone as absent rather than too short', () => {
    const parsed = EnquiryRequestSchema.safeParse({
      ...validEnquiry,
      contact: { ...validEnquiry.contact, phone: '' },
    });
    expect(parsed.success).toBe(true);
    expect(parsed.success && parsed.data.contact.phone).toBeUndefined();
  });

  it('rejects a message over 1000 characters', () => {
    expect(fieldErrorsFor({ ...validEnquiry, message: 'x'.repeat(1001) })).toEqual({
      message: 'Message is too long',
    });
  });

  it('accepts a message at exactly 1000 characters', () => {
    expect(EnquiryRequestSchema.safeParse({ ...validEnquiry, message: 'x'.repeat(1000) }).success).toBe(
      true,
    );
  });

  it('rejects a missing, non-integer or non-positive productId', () => {
    for (const productId of [undefined, 'abc', 1.5, 0, -3]) {
      const errors = fieldErrorsFor({ ...validEnquiry, productId });
      expect(errors['productId'], String(productId)).toBe('Select a product');
    }
  });

  it('reports every failing field at once, keyed by dotted path', () => {
    const errors = fieldErrorsFor({ productId: 0, contact: { name: '', email: 'nope' } });
    expect(Object.keys(errors).sort()).toEqual(['contact.email', 'contact.name', 'productId']);
  });
});

describe('seenPrice is ignored, never rejected (SPEC §3, §6)', () => {
  it('accepts a plausible value and keeps it', () => {
    const parsed = EnquiryRequestSchema.parse({ ...validEnquiry, seenPrice: 109.95 });
    expect(parsed.seenPrice).toBe(109.95);
  });

  it('accepts the enquiry when absent', () => {
    expect(EnquiryRequestSchema.parse(validEnquiry).seenPrice).toBeUndefined();
  });

  it('drops an implausible value instead of failing the enquiry', () => {
    for (const seenPrice of [-1, 0, 'free', null, {}]) {
      const parsed = EnquiryRequestSchema.safeParse({ ...validEnquiry, seenPrice });
      expect(parsed.success, String(seenPrice)).toBe(true);
      expect(parsed.success && parsed.data.seenPrice).toBeUndefined();
    }
  });

  it('never produces a fieldError for seenPrice', () => {
    const errors = fieldErrorsFor({ ...validEnquiry, productId: 0, seenPrice: -5 });
    expect(errors['seenPrice']).toBeUndefined();
  });
});

describe('search query validation', () => {
  it('accepts a two-character term and trims it', () => {
    expect(SearchQuerySchema.parse({ q: '  ab  ' }).q).toBe('ab');
  });

  it('rejects a term shorter than two characters after trimming', () => {
    for (const q of ['', ' ', 'a', ' a ']) {
      const result = SearchQuerySchema.safeParse({ q });
      expect(result.success, JSON.stringify(q)).toBe(false);
      if (!result.success) {
        expect(toFieldErrors(result.error)).toEqual({ q: 'Enter at least 2 characters' });
      }
    }
  });

  it('rejects a missing term', () => {
    expect(SearchQuerySchema.safeParse({}).success).toBe(false);
  });
});

describe('parseOrThrow', () => {
  it('raises VALIDATION_FAILED carrying the field errors', () => {
    try {
      parseOrThrow(EnquiryRequestSchema, { productId: 0 }, 'enquiry');
      throw new Error('expected a throw');
    } catch (error) {
      expect(error).toBeInstanceOf(AppError);
      const appError = error as AppError;
      expect(appError.code).toBe('VALIDATION_FAILED');
      expect(appError.httpStatus).toBe(422);
      expect(appError.fieldErrors?.['productId']).toBe('Select a product');
    }
  });

  it('names only the failing fields in developerMessage, never the values', () => {
    try {
      parseOrThrow(
        EnquiryRequestSchema,
        { productId: 1, contact: { name: 'Ada', email: 'secret-typo@@' } },
        'enquiry',
      );
      throw new Error('expected a throw');
    } catch (error) {
      const appError = error as AppError;
      expect(appError.developerMessage).toContain('contact.email');
      expect(appError.developerMessage).not.toContain('secret-typo');
    }
  });

  it('returns parsed data on success', () => {
    expect(parseOrThrow(SearchQuerySchema, { q: ' bag ' }, 'search')).toEqual({ q: 'bag' });
  });
});
