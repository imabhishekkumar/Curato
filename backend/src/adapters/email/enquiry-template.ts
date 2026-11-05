/**
 * Composing the enquiry email (SPEC.md §4.6, §10.3).
 *
 * The enquiry text reaches a human inbox, so unescaped input here is an injection vector
 * pointed at our own staff. Two separate protections: values are HTML-escaped in the
 * body, and anything reaching a mail *header* rejects CR/LF outright.
 */
import { AppError } from '../../domain/errors.js';
import type { OutboundEmail } from '../../domain/ports/email-service.js';
import type { Product } from '../../domain/product.js';

export interface EnquiryEmailInput {
  /** Resolved server-side. Never the client's idea of the product. */
  product: Product;
  contact: { name: string; email: string; phone?: string | undefined };
  message?: string | undefined;
  /** What the customer saw. Untrusted, and used only to report drift. */
  seenPrice?: number | undefined;
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => HTML_ESCAPES[char] ?? char);
}

/** Mail headers are newline-delimited, so a newline in a value is a header injection. */
function assertHeaderSafe(value: string, field: string): string {
  if (/[\r\n]/.test(value)) {
    throw new AppError('VALIDATION_FAILED', {
      developerMessage: `${field} contains a line break and cannot be used in a mail header`,
      fieldErrors: { [field]: 'Enter a valid email address' },
    });
  }
  return value;
}

/** Upstream titles are untrusted too — a subject is a header like any other. */
function flatten(value: string): string {
  return value.replace(/[\r\n]+/g, ' ').trim();
}

export function formatPrice(price: number, currency: string): string {
  return `${currency} ${price.toFixed(2)}`;
}

export function composeEnquiryEmail(input: EnquiryEmailInput): OutboundEmail {
  const { product, contact, message, seenPrice } = input;
  const replyTo = assertHeaderSafe(contact.email, 'contact.email');

  const currentPrice = formatPrice(product.price, product.currency);
  // Only a drift note — the resolved price is always the one of record.
  const priceDrifted = seenPrice !== undefined && seenPrice !== product.price;
  const priceLine = priceDrifted
    ? `${currentPrice} (customer saw ${formatPrice(seenPrice, product.currency)})`
    : currentPrice;

  const rows: Array<[string, string]> = [
    ['Product', `${product.title} (id ${product.id})`],
    ['Category', product.category.name],
    ['Price', priceLine],
    ['From', `${contact.name} <${contact.email}>`],
  ];
  if (contact.phone !== undefined) rows.push(['Phone', contact.phone]);

  const text = [
    ...rows.map(([label, value]) => `${label}: ${value}`),
    '',
    'Message:',
    message ?? '(none)',
  ].join('\n');

  const html = [
    '<h2>New product enquiry</h2>',
    '<table>',
    ...rows.map(
      ([label, value]) =>
        `<tr><td><strong>${escapeHtml(label)}</strong></td><td>${escapeHtml(value)}</td></tr>`,
    ),
    '</table>',
    '<h3>Message</h3>',
    `<p>${escapeHtml(message ?? '(none)')}</p>`,
  ].join('\n');

  return {
    subject: flatten(`Enquiry: ${product.title}`),
    text,
    html,
    replyTo,
  };
}
