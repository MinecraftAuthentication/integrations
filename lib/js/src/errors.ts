/** Thrown for every failed API request. `status` is 0 when no response was received. */
export class MinecraftAuthError extends Error {
  override readonly name: string = "MinecraftAuthError";
  readonly status: number;

  constructor(status: number, message: string, options?: { cause?: unknown }) {
    super(status === 0 ? `mcauth: ${message}` : `mcauth: HTTP ${status}: ${message}`, options);
    this.status = status;
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

/** Builds the error for a non-2xx response, preferring the API's `{"error": "..."}` message. */
export function errorForStatus(status: number, body: string): MinecraftAuthError {
  let message = STATUS_TEXT[status] ?? `HTTP ${status}`;
  try {
    const parsed: unknown = JSON.parse(body);
    if (isRecord(parsed) && typeof parsed["error"] === "string" && parsed["error"]) message = parsed["error"];
  } catch {
    // not JSON: keep the status text
  }
  return new MinecraftAuthError(status, message);
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
