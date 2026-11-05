/**
 * `EmailService` over SMTP (AGENTS.md "Architecture").
 *
 * Mailhog locally, a real provider later as a different implementation of the same
 * interface. Nodemailer's `sendMail` resolves only once the server has accepted the
 * message, which is precisely the guarantee the enquiry endpoint needs.
 */
import { createTransport, type Transporter } from 'nodemailer';
import { AppError } from '../../domain/errors.js';
import type { EmailService, OutboundEmail } from '../../domain/ports/email-service.js';
import { logger } from '../../logger.js';

export interface SmtpEmailServiceOptions {
  host: string;
  port: number;
  from: string;
  to: string;
}

export class SmtpEmailService implements EmailService {
  readonly #transport: Transporter;
  readonly #from: string;
  readonly #to: string;

  constructor(options: SmtpEmailServiceOptions) {
    this.#from = options.from;
    this.#to = options.to;
    this.#transport = createTransport({
      host: options.host,
      port: options.port,
      // Mailhog speaks plain SMTP with no auth. A real provider swaps this adapter
      // rather than adding conditionals here.
      secure: false,
      ignoreTLS: true,
    });
  }

  async send(message: OutboundEmail): Promise<void> {
    try {
      await this.#transport.sendMail({
        from: this.#from,
        to: this.#to,
        subject: message.subject,
        text: message.text,
        html: message.html,
        ...(message.replyTo !== undefined ? { replyTo: message.replyTo } : {}),
      });
    } catch (cause) {
      // The SMTP detail goes to the log, never to the response body.
      logger.error('SMTP rejected the enquiry', { cause });
      throw new AppError('EMAIL_SEND_FAILED', {
        developerMessage: 'the mail server did not accept the message',
        cause,
      });
    }
  }

  /** A reachability probe for `GET /health`. Never throws. */
  async isReachable(): Promise<boolean> {
    try {
      await this.#transport.verify();
      return true;
    } catch {
      return false;
    }
  }
}
