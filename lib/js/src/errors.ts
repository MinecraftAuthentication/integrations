/** Thrown for every failed API request. `status` is 0 when no response was received. */
export class MinecraftAuthError extends Error {
  override readonly name: string = "MinecraftAuthError";
  readonly status: number;

  constructor(status: number, message: string, options?: { cause?: unknown }) {
    super(status === 0 ? `mcauth: ${message}` : `mcauth: HTTP ${status}: ${message}`, options);
    this.status = status;
  }
}

/**
 * The API is rate limiting this client (429 with `Retry-After`), and waiting it out would take longer than
 * `maxRateLimitWaitMs`. Shorter waits are retried automatically. Until `retryAfterMs` has passed, the client rejects
 * calls with this error without sending them.
 */
export class RateLimitError extends MinecraftAuthError {
  override readonly name: string = "RateLimitError";
  /** How long to wait before trying again, in milliseconds. */
  readonly retryAfterMs: number;

  constructor(message: string, retryAfterMs: number) {
    super(429, message);
    this.retryAfterMs = retryAfterMs;
  }
}

/** Thrown by {@link verifyGrantToken} for every verification failure. */
export class InvalidTokenError extends Error {
  override readonly name: string = "InvalidTokenError";

  constructor(message: string, options?: { cause?: unknown }) {
    super(`mcauth: invalid grant token: ${message}`, options);
  }
}

function statusOf(err: unknown): number | undefined {
  return err instanceof MinecraftAuthError ? err.status : undefined;
}

/** A 404: the resource doesn't exist, or the player's privacy settings hide it. */
export function isNotFound(err: unknown): boolean {
  return statusOf(err) === 404;
}

/** A 400/401/403: a problem with how the integration is set up. Retrying won't help. */
export function isConfig(err: unknown): boolean {
  const status = statusOf(err);
  return status === 400 || status === 401 || status === 403;
}

/** The API is rate limiting the client; see {@link RateLimitError}. */
export function isRateLimited(err: unknown): err is RateLimitError {
  return err instanceof RateLimitError;
}

/** A failure worth retrying later: 409 (bot not in the Discord server), 429, 5xx, or no response at all. */
export function isTemporary(err: unknown): boolean {
  const status = statusOf(err);
  return status !== undefined && (status === 0 || status === 409 || status === 429 || status >= 500);
}

const STATUS_TEXT: Record<number, string> = {
  400: "Bad Request",
  401: "Unauthorized",
  403: "Forbidden",
  404: "Not Found",
  409: "Conflict",
  429: "Too Many Requests",
  500: "Internal Server Error",
  502: "Bad Gateway",
  503: "Service Unavailable",
  504: "Gateway Timeout",
};

/**
 * Builds the error for a non-2xx response, preferring the API's `{"error": "..."}` message. A 429 with `retryAfterMs`
 * is a {@link RateLimitError}; one without is a cap, like on unanswered access requests.
 */
export function errorForStatus(status: number, body: string, retryAfterMs?: number): MinecraftAuthError {
  let message = STATUS_TEXT[status] ?? `HTTP ${status}`;
  try {
    const parsed: unknown = JSON.parse(body);
    if (isRecord(parsed) && typeof parsed["error"] === "string" && parsed["error"]) message = parsed["error"];
  } catch {
    // not JSON: keep the status text
  }
  if (status === 429 && retryAfterMs !== undefined) return new RateLimitError(message, retryAfterMs);
  return new MinecraftAuthError(status, message);
}

/** A `Retry-After` header in milliseconds: delay-seconds or an HTTP date. `undefined` if missing or malformed. */
export function parseRetryAfter(value: string | null | undefined, now: number = Date.now()): number | undefined {
  const trimmed = value?.trim();
  if (!trimmed) return undefined;
  if (/^\d+$/.test(trimmed)) return Number(trimmed) * 1000;
  if (/^-?\d+$/.test(trimmed)) return undefined;
  const at = Date.parse(trimmed);
  return Number.isNaN(at) ? undefined : Math.max(0, at - now);
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
