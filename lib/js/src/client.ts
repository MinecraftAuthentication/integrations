import { anonymous, type Credentials } from "./credentials.js";
import { MinecraftAuthError, errorForStatus, isNotFound, isRecord } from "./errors.js";
import type { Platform } from "./platform.js";

/** The MinecraftAuth.me API host. Every path already includes `/api/v2`. */
export const DEFAULT_BASE_URL = "https://minecraftauth.me";

const MAX_BODY_BYTES = 1 << 20;

export interface MinecraftAuthOptions {
  /** Required by the API. Name your integration and its version, e.g. `MyBot/1.2`. */
  userAgent: string;
  /** Defaults to anonymous access, which only allows {@link MinecraftAuth.lookup}. */
  credentials?: Credentials;
  /** Defaults to {@link DEFAULT_BASE_URL}. */
  baseUrl?: string;
  /** Per-request timeout in milliseconds (default 10 000; 0 disables it). */
  timeoutMs?: number;
  /** Replaces the global `fetch`, e.g. for tests or a custom agent. */
  fetch?: typeof globalThis.fetch;
}

/** Options every call accepts. */
export interface RequestOptions {
  /** Aborts the call. An aborted call rejects with the signal's reason. */
  signal?: AbortSignal;
}

/** An account linked to an identity: either `identifier` is set, or `hint` is true (linked, but not which account). */
export interface LinkedAccount {
  identifier?: string;
  hint?: boolean;
}

/**
 * Maps platforms to the visible linked accounts. A missing platform means unlinked, hidden, or not requested;
 * it never proves the player has no account there.
 */
export interface LookupResult {
  accounts: Partial<Record<Platform, LinkedAccount>>;
}

/** A newly created access request. Show the player `url`, or `code` together with `verificationUrl`. */
export interface GrantRequest {
  id: string;
  code: string;
  url: string;
  verificationUrl: string;
  scopes: string[];
  persist: boolean;
  /** Epoch milliseconds. */
  expiresAt: number;
  /** Seconds to wait between polls. */
  interval: number;
}

export type GrantStatus = "pending" | "accepted" | "denied" | "expired";

/** The result of polling an access request. `token` is set once accepted. */
export interface GrantRequestStatus {
  status: GrantStatus;
  /** Epoch milliseconds. */
  expiresAt: number;
  /** The signed grant token (JWT); verify it with {@link verifyGrantToken} if it came through a browser. */
  token?: string;
}

/**
 * Client for the MinecraftAuth.me v2 API (https://minecraftauth.me/developers).
 *
 * Membership checks ({@link following}, {@link subscribed}, {@link discordPresent}) resolve to `false` when the
 * player isn't linked or hides the account (HTTP 404). Other failures reject with a {@link MinecraftAuthError};
 * classify them with `isConfig` / `isTemporary`.
 */
export class MinecraftAuth {
  readonly baseUrl: string;
  readonly #userAgent: string;
  readonly #credentials: Credentials;
  readonly #timeoutMs: number;
  readonly #fetch: typeof globalThis.fetch;

  constructor(options: MinecraftAuthOptions) {
    if (!options.userAgent?.trim()) {
      throw new TypeError("A userAgent naming your integration is required by the MinecraftAuth.me API");
    }
    this.#userAgent = options.userAgent;
    this.#credentials = options.credentials ?? anonymous;
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
    this.#timeoutMs = options.timeoutMs ?? 10_000;
    this.#fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
  }

  /**
   * The accounts linked to the identity owning `identifier` on `platform`, or `null` if there is no such
   * identity (or it's hidden from you). `include` limits the result to those platforms.
   */
  async lookup(
    platform: Platform,
    identifier: string,
    options: RequestOptions & { include?: readonly Platform[] } = {},
  ): Promise<LookupResult | null> {
    const query = options.include?.length ? { include: options.include.join(",") } : undefined;
    try {
      const out = await this.#json("GET", `/api/v2/lookup/${platform}/${encodeURIComponent(identifier)}`, query, undefined, options);
      const accounts = isRecord(out) && isRecord(out["accounts"]) ? out["accounts"] : {};
      return { accounts: accounts as LookupResult["accounts"] };
    } catch (err) {
      if (isNotFound(err)) return null;
      throw err;
    }
  }

  /**
   * Whether the player follows you: Twitch = follows your channel, Google = subscribed to your YouTube channel.
   * A player who isn't linked (HTTP 404) is reported as `false`.
   */
  following(minecraft: string, platform: Platform, options?: RequestOptions): Promise<boolean> {
    return this.#membership("/api/v2/following", { minecraft, platform }, options);
  }

  /**
   * Whether the player subscribes to or supports you on `platform`. `data` narrows the check: a Discord role ID
   * (required for Discord; use {@link discordRole} for a role name), a minimum Twitch tier "1"-"3", or a Patreon
   * tier title.
   */
  subscribed(minecraft: string, platform: Platform, data?: string, options?: RequestOptions): Promise<boolean> {
    const query: Record<string, string> = { minecraft, platform };
    if (data) query["data"] = data;
    return this.#membership("/api/v2/subscribed", query, options);
  }

  /**
   * Whether the player's Discord account has `role` in a Discord server you moderate. `role` is a role ID, or a role
   * name matched ignoring case. A name needs `serverId`: anyone can create a role with the same name in a server of
   * their own, so the API only looks names up in the server you name.
   */
  discordRole(minecraft: string, role: string, serverId?: string, options?: RequestOptions): Promise<boolean> {
    const query: Record<string, string> = { minecraft, platform: "discord", data: role };
    if (serverId) query["server"] = serverId;
    return this.#membership("/api/v2/subscribed", query, options);
  }

  /** Whether the player's Discord account is a member of the Discord server `serverId`. */
  discordPresent(minecraft: string, serverId: string, options?: RequestOptions): Promise<boolean> {
    return this.#membership("/api/v2/discord/present", { minecraft, server: serverId }, options);
  }

  /** Starts an access request (requires application credentials). `minecraft` is always in scope. */
  async createGrantRequest(
    options: RequestOptions & { scopes?: readonly Platform[]; persist?: boolean } = {},
  ): Promise<GrantRequest> {
    const body = { scopes: options.scopes ?? [], persist: options.persist ?? true };
    const out = await this.#json("POST", "/api/v2/grant-requests", undefined, body, options);
    if (!isRecord(out) || typeof out["id"] !== "string") {
      throw new MinecraftAuthError(502, "grant request response without an id");
    }
    return out as unknown as GrantRequest;
  }

  /** Polls an access request started by this application. */
  async getGrantRequest(id: string, options?: RequestOptions): Promise<GrantRequestStatus> {
    const out = await this.#json("GET", `/api/v2/grant-requests/${encodeURIComponent(id)}`, undefined, undefined, options);
    if (!isRecord(out)) throw new MinecraftAuthError(502, "invalid grant request status");
    const status: GrantStatus = ["pending", "accepted", "denied"].includes(out["status"] as string)
      ? (out["status"] as GrantStatus)
      : "expired";
    const result: GrantRequestStatus = { status, expiresAt: Number(out["expiresAt"]) || 0 };
    if (typeof out["token"] === "string" && out["token"]) result.token = out["token"];
    return result;
  }

  /**
   * Polls `request` at its advertised interval until the player answers or it expires. Resolves with the final
   * status; abort it with `signal`.
   */
  async awaitGrant(request: GrantRequest, options: RequestOptions = {}): Promise<GrantRequestStatus> {
    const intervalMs = Math.max(1, request.interval || 0) * 1000;
    for (;;) {
      const status = await this.getGrantRequest(request.id, options);
      if (status.status !== "pending") return status;
      if (Date.now() + intervalMs > status.expiresAt) return { ...status, status: "expired" };
      await sleep(intervalMs, options.signal);
    }
  }

  /** Downloads the PEM-encoded RSA public key that signs grant tokens. Usually used through {@link KeySource}. */
  async fetchPublicKeyPem(options?: RequestOptions): Promise<string> {
    const { status, body } = await this.#send("GET", "/rsa", undefined, undefined, options, "text/plain, */*");
    if (status < 200 || status > 299) throw errorForStatus(status, body);
    return body;
  }

  async #membership(path: string, query: Record<string, string>, options?: RequestOptions): Promise<boolean> {
    let out: unknown;
    try {
      out = await this.#json("GET", path, query, undefined, options);
    } catch (err) {
      if (isNotFound(err)) return false;
      throw err;
    }
    if (!isRecord(out) || typeof out["result"] !== "boolean") {
      throw new MinecraftAuthError(502, "membership response without a result");
    }
    return out["result"];
  }

  async #json(
    method: string,
    path: string,
    query: Record<string, string> | undefined,
    body: unknown,
    options: RequestOptions | undefined,
  ): Promise<unknown> {
    const response = await this.#send(method, path, query, body, options, "application/json");
    if (response.status < 200 || response.status > 299) throw errorForStatus(response.status, response.body);
    if (!response.body) return undefined;
    try {
      return JSON.parse(response.body);
    } catch (err) {
      throw new MinecraftAuthError(response.status, `invalid JSON response: ${(err as Error).message}`, { cause: err });
    }
  }

  async #send(
    method: string,
    path: string,
    query: Record<string, string> | undefined,
    body: unknown,
    options: RequestOptions | undefined,
    accept: string,
  ): Promise<{ status: number; body: string }> {
    let url = this.baseUrl + path;
    if (query) url += `?${new URLSearchParams(query)}`;
    const headers: Record<string, string> = { "User-Agent": this.#userAgent, Accept: accept };
    const authorization = this.#credentials.authorization();
    if (authorization) headers["Authorization"] = authorization;
    const init: RequestInit = { method, headers, redirect: "follow" };
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }

    const userSignal = options?.signal;
    const timeout = this.#timeoutMs > 0 ? AbortSignal.timeout(this.#timeoutMs) : undefined;
    const signals = [userSignal, timeout].filter((s): s is AbortSignal => s !== undefined);
    if (signals.length) init.signal = signals.length === 1 ? signals[0]! : AbortSignal.any(signals);

    try {
      const response = await this.#fetch(url, init);
      return { status: response.status, body: await readLimited(response) };
    } catch (err) {
      if (userSignal?.aborted) throw userSignal.reason;
      if (err instanceof MinecraftAuthError) throw err;
      const reason = timeout?.aborted ? `timed out after ${this.#timeoutMs} ms` : (err as Error)?.message ?? String(err);
      throw new MinecraftAuthError(0, `${method} ${path}: ${reason}`, { cause: err });
    }
  }
}

async function readLimited(response: Response): Promise<string> {
  if (!response.body) return "";
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let size = 0;
  let text = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > MAX_BODY_BYTES) {
      await reader.cancel().catch(() => {});
      throw new MinecraftAuthError(response.status, `response larger than ${MAX_BODY_BYTES} bytes`);
    }
    text += decoder.decode(value, { stream: true });
  }
  return text + decoder.decode();
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal!.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}
