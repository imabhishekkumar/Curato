/**
 * Request validation (SPEC.md §6).
 *
 * Enforced server-side and authoritatively, independently of the client's own checks and
 * including values the client prefilled from its own store. The messages here are the
 * ones rendered under each input, so they are user-facing copy and match the spec's
 * table exactly.
 *
 * Zod earns its place on one detail: `issue.path.join('.')` *is* the dotted `fieldErrors`
 * key the envelope requires, so there is no translation layer to get wrong.
 */
import { z } from 'zod';
import { AppError, type FieldErrors } from '../domain/errors.js';
import { MIN_QUERY_LENGTH } from '../domain/search.js';

/** An absent optional field and an empty one mean the same thing to a customer. */
const blankToUndefined = (value: unknown): unknown =>
  typeof value === 'string' && value.trim() === '' ? undefined : value;

const PHONE_SHAPE = /^[\d+\-() ]+$/;

const ContactSchema = z.object({
  name: z
    .string({ error: 'Enter your name' })
    .transform((value) => value.trim())
    .refine((value) => value.length >= 2 && value.length <= 80, { error: 'Enter your name' }),
  email: z
    .string({ error: 'Enter a valid email address' })
    .transform((value) => value.trim())
    .refine((value) => value.length <= 254, { error: 'Enter a valid email address' })
    .pipe(z.email({ error: 'Enter a valid email address' })),
  phone: z.preprocess(
    blankToUndefined,
    z
      .string({ error: 'Enter a valid phone number' })
      .transform((value) => value.trim())
      .refine(
        (value) => value.length >= 7 && value.length <= 20 && PHONE_SHAPE.test(value),
        { error: 'Enter a valid phone number' },
      )
      .optional(),
  ),
});

export const EnquiryRequestSchema = z.object({
  productId: z
    .number({ error: 'Select a product' })
    .int({ error: 'Select a product' })
    .positive({ error: 'Select a product' }),
  /**
   * Untrusted and **ignored when absent or implausible, never rejected**.
   *
   * It is a diagnostic, not an input: failing an enquiry over a field that has no
   * authority would block a real customer for a value we were never going to use.
   * `.catch` is what makes an implausible value an absence rather than a 422.
   */
  seenPrice: z.number().positive().optional().catch(undefined),
  contact: ContactSchema,
  message: z.preprocess(
    blankToUndefined,
    z.string().max(1000, { error: 'Message is too long' }).optional(),
  ),
});

export type EnquiryRequest = z.infer<typeof EnquiryRequestSchema>;

export const SearchQuerySchema = z.object({
  q: z
    .string({ error: `Enter at least ${MIN_QUERY_LENGTH} characters` })
    .transform((value) => value.trim())
    .refine((value) => value.length >= MIN_QUERY_LENGTH, {
      error: `Enter at least ${MIN_QUERY_LENGTH} characters`,
    }),
});

/**
 * Zod issues → `fieldErrors`.
 *
 * Keys are dotted request paths (`contact.email`) so the client places each error under
 * the input that caused it without guessing. First issue per field wins — showing two
 * messages under one input is noise.
 */
export function toFieldErrors(error: z.ZodError): FieldErrors {
  const fieldErrors: FieldErrors = {};
  for (const issue of error.issues) {
    const key = issue.path.map(String).join('.');
    if (key !== '' && fieldErrors[key] === undefined) fieldErrors[key] = issue.message;
  }
  return fieldErrors;
}

/**
 * Parse a request, or raise `VALIDATION_FAILED` carrying every field's message.
 *
 * Validation only. Whether a well-formed `productId` *resolves* is a different question
 * with a different failure — see SPEC §6, "Validation is not resolution".
 */
export function parseOrThrow<T>(schema: z.ZodType<T>, input: unknown, context: string): T {
  const result = schema.safeParse(input);
  if (result.success) return result.data;

  const fieldErrors = toFieldErrors(result.error);
  throw new AppError('VALIDATION_FAILED', {
    developerMessage: `${context} failed validation: ${Object.keys(fieldErrors).join(', ')}`,
    fieldErrors,
  });
}
