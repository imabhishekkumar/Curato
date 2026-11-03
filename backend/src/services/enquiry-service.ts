/**
 * The enquiry path (SPEC.md §4.6).
 *
 * The only state-changing flow in the system, and the only conversion point in the
 * product. Processing order is part of the contract: validate → **resolve the product
 * server-side** → compose → send → respond.
 *
 * Resolution before sending is what stops an enquiry about a product we no longer know
 * from reaching the sales inbox, and the response is returned only after the mail server
 * accepts — never on mere receipt of the request, which is what makes the client's
 * "Thank you" truthful.
 */
import { composeEnquiryEmail } from '../adapters/email/enquiry-template.js';
import type { EmailService } from '../domain/ports/email-service.js';
import type { ProductRepository } from '../domain/ports/product-repository.js';
import type { EnquiryRequest } from '../http/validation.js';
import { AppError } from '../domain/errors.js';
import { logger } from '../logger.js';

export interface EnquiryAccepted {
  acceptedAt: string;
}

export class EnquiryService {
  readonly #products: ProductRepository;
  readonly #email: EmailService;

  constructor(products: ProductRepository, email: EmailService) {
    this.#products = products;
    this.#email = email;
  }

  async submit(request: EnquiryRequest): Promise<EnquiryAccepted> {
    // The request carries an id and nothing else about the product. Title, price and
    // category come from here, so a caller cannot submit a real product with an
    // invented price and have us email it to sales as fact.
    const product = await this.#products.getById(request.productId);
    if (product === undefined) {
      // Not a field error: nothing the customer can retype fixes a removed product, and
      // the client must take the non-retryable path.
      throw new AppError('NOT_FOUND', {
        developerMessage: 'enquiry references a product that does not resolve',
      });
    }

    const message = composeEnquiryEmail({
      product,
      contact: request.contact,
      message: request.message,
      seenPrice: request.seenPrice,
    });

    if (request.seenPrice !== undefined && request.seenPrice !== product.price) {
      logger.info('enquiry price drift', {
        productId: product.id,
        resolvedPrice: product.price,
        seenPrice: request.seenPrice,
      });
    }

    await this.#email.send(message);

    // Nothing is persisted, so no reference id is returned — an id the customer could
    // not look up would be theatre.
    return { acceptedAt: new Date().toISOString() };
  }
}
