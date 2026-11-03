/**
 * Email delivery, behind an interface (AGENTS.md "Architecture").
 *
 * The second boundary expected to change: Mailhog locally today, a real provider later,
 * as a new implementation rather than a rewrite. It also exists so tests never need an
 * SMTP server.
 */

export interface OutboundEmail {
  subject: string;
  /** Plain-text body. Always populated — some inboxes render nothing else. */
  text: string;
  /** HTML body, with every customer-supplied value escaped. */
  html: string;
  /**
   * The customer's address, so replying from the sales inbox reaches them.
   * Rejected upstream of here if it contains CR or LF — see the enquiry template.
   */
  replyTo?: string;
}

export interface EmailService {
  /**
   * Resolves **only once the server has accepted the message** for delivery, and rejects
   * otherwise.
   *
   * This is the whole basis of AC-12: the enquiry endpoint returns 2xx after this
   * resolves and never on mere receipt of the request, which is what makes the client's
   * "Thank you" truthful.
   */
  send(message: OutboundEmail): Promise<void>;
}
