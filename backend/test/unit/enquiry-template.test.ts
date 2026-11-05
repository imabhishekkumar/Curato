import { describe, expect, it } from 'vitest';
import {
  composeEnquiryEmail,
  escapeHtml,
  formatPrice,
} from '../../src/adapters/email/enquiry-template.js';
import { makeProduct } from '../fixtures/builders.js';

const product = makeProduct({ id: 7, title: 'Mens Casual Slim Fit', price: 15.99 });
const contact = { name: 'Ada Lovelace', email: 'ada@example.com' };

describe('escapeHtml', () => {
  it('escapes every character that could become markup', () => {
    expect(escapeHtml('<a href="x">&\'</a>')).toBe(
      '&lt;a href=&quot;x&quot;&gt;&amp;&#39;&lt;/a&gt;',
    );
  });

  it('leaves ordinary text alone', () => {
    expect(escapeHtml('Is this available in black?')).toBe('Is this available in black?');
  });
});

describe('formatPrice', () => {
  it('states the currency rather than relying on a symbol', () => {
    expect(formatPrice(109.95, 'USD')).toBe('USD 109.95');
  });

  it('always shows two decimal places', () => {
    expect(formatPrice(15, 'USD')).toBe('USD 15.00');
    expect(formatPrice(15.5, 'USD')).toBe('USD 15.50');
  });
});

describe('composeEnquiryEmail', () => {
  it('names the product and its id in the subject and body', () => {
    const email = composeEnquiryEmail({ product, contact });
    expect(email.subject).toBe('Enquiry: Mens Casual Slim Fit');
    expect(email.text).toContain('id 7');
  });

  it('always populates both a text and an HTML body', () => {
    const email = composeEnquiryEmail({ product, contact });
    expect(email.text.length).toBeGreaterThan(0);
    expect(email.html.length).toBeGreaterThan(0);
  });

  it('includes the phone only when given', () => {
    expect(composeEnquiryEmail({ product, contact }).text).not.toContain('Phone:');
    expect(
      composeEnquiryEmail({ product, contact: { ...contact, phone: '+91 9876543210' } }).text,
    ).toContain('Phone: +91 9876543210');
  });

  it('says so explicitly when there is no message', () => {
    expect(composeEnquiryEmail({ product, contact }).text).toContain('(none)');
  });

  it('reports drift without ever replacing the resolved price', () => {
    const email = composeEnquiryEmail({ product, contact, seenPrice: 1.0 });
    expect(email.text).toContain('Price: USD 15.99 (customer saw USD 1.00)');
  });

  it('flattens a newline in an upstream title, which would otherwise split the header', () => {
    const email = composeEnquiryEmail({
      product: makeProduct({ title: 'Shirt\r\nBcc: victim@example.com' }),
      contact,
    });
    expect(email.subject).not.toMatch(/[\r\n]/);
    expect(email.subject).toBe('Enquiry: Shirt Bcc: victim@example.com');
  });

  it('refuses a reply-to containing CR or LF', () => {
    expect(() =>
      composeEnquiryEmail({
        product,
        contact: { ...contact, email: 'ada@example.com\nBcc: victim@example.com' },
      }),
    ).toThrowError(expect.objectContaining({ code: 'VALIDATION_FAILED' }));
  });

  it('escapes the customer name and message in the HTML body', () => {
    const email = composeEnquiryEmail({
      product,
      contact: { ...contact, name: '<b>Ada</b>' },
      message: '<img src=x onerror=alert(1)>',
    });
    expect(email.html).not.toContain('<b>Ada</b>');
    expect(email.html).not.toContain('<img');
    expect(email.html).toContain('&lt;img');
  });
});
