/**
 * An `EmailService` that records instead of sending.
 *
 * Lets the suite assert on what *would* reach the sales inbox — which is where the
 * backend-resolved price has to appear — without an SMTP server anywhere near it.
 */
import { AppError } from '../../src/domain/errors.js';
import type { EmailService, OutboundEmail } from '../../src/domain/ports/email-service.js';

export class FakeEmailService implements EmailService {
  readonly sent: OutboundEmail[] = [];
  /** When set, `send` rejects with it — the SMTP-rejected path. */
  failure: AppError | undefined;
  /** Resolves only when released, for testing non-re-entrancy. */
  gate: Promise<void> | undefined;

  static rejecting(): FakeEmailService {
    const service = new FakeEmailService();
    service.failure = new AppError('EMAIL_SEND_FAILED', {
      developerMessage: 'the mail server did not accept the message',
    });
    return service;
  }

  get lastSent(): OutboundEmail | undefined {
    return this.sent.at(-1);
  }

  async send(message: OutboundEmail): Promise<void> {
    if (this.gate) await this.gate;
    if (this.failure) throw this.failure;
    this.sent.push(message);
  }
}
