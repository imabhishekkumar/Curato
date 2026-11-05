/**
 * Structured logging.
 *
 * Raw diagnostics live here and are never serialized into a response. `traceId`
 * correlates a customer-visible failure with the log line holding the real cause, which
 * is the only safe way to get from one to the other.
 */
const LEVELS = ['debug', 'info', 'warn', 'error', 'silent'] as const;

type Level = (typeof LEVELS)[number];

interface LogFields {
  traceId?: string;
  [key: string]: unknown;
}

function configuredLevel(): Level {
  const raw = process.env['LOG_LEVEL']?.toLowerCase();
  return LEVELS.includes(raw as Level) ? (raw as Level) : 'info';
}

/**
 * Read once at import. A log level that could change mid-process would make two lines
 * from the same request disagree about whether they exist.
 */
const threshold = LEVELS.indexOf(configuredLevel());

function emit(level: Exclude<Level, 'silent'>, message: string, fields: LogFields = {}): void {
  if (LEVELS.indexOf(level) < threshold) return;

  const line = JSON.stringify(
    { level, time: new Date().toISOString(), message, ...fields },
    replaceErrors,
  );
  if (level === 'error' || level === 'warn') process.stderr.write(`${line}\n`);
  else process.stdout.write(`${line}\n`);
}

/** Errors do not survive `JSON.stringify` by default; keep the detail for the log. */
function replaceErrors(_key: string, value: unknown): unknown {
  if (value instanceof Error) {
    return { name: value.name, message: value.message, stack: value.stack };
  }
  return value;
}

export const logger = {
  debug: (message: string, fields?: LogFields) => emit('debug', message, fields),
  info: (message: string, fields?: LogFields) => emit('info', message, fields),
  warn: (message: string, fields?: LogFields) => emit('warn', message, fields),
  error: (message: string, fields?: LogFields) => emit('error', message, fields),
};

export type Logger = typeof logger;
