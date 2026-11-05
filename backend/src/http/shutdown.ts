/**
 * Graceful shutdown.
 *
 * `docker-compose.yml` sets `restart: unless-stopped`, so this process is sent SIGTERM
 * on every `docker compose down` and every restart. Without a handler Node tears the
 * process down with requests still in flight — including an enquiry whose message SMTP
 * has already accepted, where the customer would see a failure for an email that was
 * actually sent. That is the one shutdown case with a user-visible consequence, and it
 * is why this exists.
 */
import { logger } from '../logger.js';

/** The slice of `http.Server` this needs, so a test can supply a fake. */
export interface ClosableServer {
  close(callback: (error?: Error) => void): unknown;
  /** Keep-alive sockets would otherwise hold the server open for the whole grace period. */
  closeIdleConnections?(): void;
}

export interface ShutdownOptions {
  /** How long in-flight requests get to finish before the process exits anyway. */
  graceMs: number;
  signals?: NodeJS.Signals[];
  /** Injected so tests observe the exit code instead of ending the run. */
  onExit?: (code: number) => void;
}

const DEFAULT_SIGNALS: NodeJS.Signals[] = ['SIGTERM', 'SIGINT'];

/**
 * Attach the handlers. Returns a disposer that removes them again.
 *
 * Idempotent on purpose: an impatient second Ctrl-C must not start a second shutdown
 * and race the first one's exit.
 */
export function installShutdownHandlers(
  server: ClosableServer,
  options: ShutdownOptions,
): () => void {
  const signals = options.signals ?? DEFAULT_SIGNALS;
  const onExit = options.onExit ?? ((code: number) => process.exit(code));
  let shuttingDown = false;
  let settled = false;

  /**
   * Report the outcome exactly once.
   *
   * The forced exit and a late `close` callback can both fire — a request that finishes
   * just after the grace period expires does exactly that. Whichever lands first is the
   * truth; without this guard a late clean close would report success over a shutdown
   * that had already abandoned work. In production `process.exit` happens to make the
   * second call unreachable, but the invariant belongs in the code rather than resting
   * on that.
   */
  const exit = (code: number): void => {
    if (settled) return;
    settled = true;
    onExit(code);
  };

  const shutdown = (signal: string): void => {
    if (shuttingDown) {
      logger.debug('shutdown already in progress', { signal });
      return;
    }
    shuttingDown = true;
    logger.info('shutting down', { signal, graceMs: options.graceMs });

    const forced = setTimeout(() => {
      // Exiting non-zero says plainly that work was abandoned, rather than reporting a
      // clean stop we did not achieve.
      logger.warn('grace period expired with requests still in flight — exiting', { signal });
      exit(1);
    }, options.graceMs);
    // Do not let the timer itself keep the event loop alive.
    forced.unref?.();

    server.close((error) => {
      clearTimeout(forced);
      if (error) {
        logger.error('server did not close cleanly', { cause: error });
        exit(1);
        return;
      }
      logger.info('shutdown complete');
      exit(0);
    });

    // Called after `close` so no new connection slips in between the two.
    server.closeIdleConnections?.();
  };

  const handlers = signals.map((signal) => {
    const handler = (): void => shutdown(signal);
    process.on(signal, handler);
    return { signal, handler };
  });

  return () => {
    for (const { signal, handler } of handlers) process.off(signal, handler);
  };
}
