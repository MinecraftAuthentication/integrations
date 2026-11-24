import type { MinecraftAuth, RequestOptions } from "./client.js";
import { InvalidTokenError, isRecord } from "./errors.js";
import { PLATFORMS, type Platform } from "./platform.js";

/** The `iss` claim of grant tokens. */
export const ISSUER = "MinecraftAuth.me";

const ALGORITHM = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-512" } as const;

/** An account included in a grant token. `name` and `avatar` may be missing. */
export interface GrantAccount {
  identifier: string;
  name?: string;
  avatar?: string;
}

/** The verified contents of a grant token. */
export interface GrantClaims {
  /** The player's MinecraftAuth.me identity ID: stable, use it as your user key. */
  subject: string;
  issuedAt: Date;
  expiresAt: Date;
  /** The ID of the application the token was issued to. */
  audience: string[];
  accounts: Partial<Record<Platform, GrantAccount>>;
}

export interface KeySourceOptions {
  /** Minimum time between refetches triggered by a signature mismatch (default 60 000 ms). */
  refreshIntervalMs?: number;
}

/**
 * Provides the RSA key that signs grant tokens, fetched from `{baseUrl}/rsa` and cached. A signature mismatch
 * triggers a refetch (at most once per `refreshIntervalMs`) in case the key rotated.
 */
export class KeySource {
  readonly #client: MinecraftAuth;
  readonly #refreshIntervalMs: number;
  #key: CryptoKey | undefined;
  #pem: string | undefined;
  #pending: Promise<CryptoKey> | undefined;
  #fetchedAt = -Infinity;

  constructor(client: MinecraftAuth, options: KeySourceOptions = {}) {
    this.#client = client;
    this.#refreshIntervalMs = options.refreshIntervalMs ?? 60_000;
  }

  /** The cached key, fetched the first time. Concurrent callers share one request. */
  key(options?: RequestOptions): Promise<CryptoKey> {
    if (this.#key) return Promise.resolve(this.#key);
    return this.#pending ?? this.#fetch(options);
  }

  /** Refetches the key unless it was fetched within the refresh interval. Resolves to whether it changed. */
  async refresh(options?: RequestOptions): Promise<boolean> {
    const old = this.#pem;
    if (!this.#pending) {
      if (Date.now() - this.#fetchedAt < this.#refreshIntervalMs) return false;
      this.#fetch(options).catch(() => {});
    }
    try {
      await this.#pending;
    } catch {
      return false;
    }
    return this.#pem !== old;
  }

  #fetch(options?: RequestOptions): Promise<CryptoKey> {
    this.#fetchedAt = Date.now();
    const pending = (async () => {
      const pem = await this.#client.fetchPublicKeyPem(options);
      const key = await importPublicKey(pem);
      this.#key = key;
      this.#pem = pem.trim();
      return key;
    })();
    this.#pending = pending;
    const clear = () => {
      if (this.#pending === pending) this.#pending = undefined;
    };
    pending.then(clear, clear);
    return pending;
  }
}

export interface VerifyOptions extends RequestOptions {
  /**
   * Also rejects tokens whose `iat` is older than this many milliseconds. Every token must carry an `exp` claim
   * and not have expired, whatever this is.
   */
  maxAgeMs?: number;
  /**
   * Your application ID. When set, it must be among the token's `aud` values, so a token issued to another
   * application is rejected. Set it whenever tokens reach you through a browser.
   */
  audience?: string;
  /** Allowed clock skew in milliseconds (default 60 000). */
  leewayMs?: number;
  /** Overrides the clock (tests). */
  now?: () => Date;
}

/**
 * Checks a grant token's RS512 signature, issuer and age, and returns its claims. Every failure, including not
 * being able to fetch the key, rejects with an {@link InvalidTokenError} (the original error is its `cause`).
 */
export async function verifyGrantToken(keys: KeySource, token: string, options: VerifyOptions = {}): Promise<GrantClaims> {
  try {
    const parts = token.trim().split(".");
    if (parts.length !== 3) throw new InvalidTokenError("not a JWT");
    const [headerPart, payloadPart, signaturePart] = parts as [string, string, string];
    const header = decodeJson(headerPart, "header");
    if (header["alg"] !== "RS512") throw new InvalidTokenError(`unexpected algorithm ${JSON.stringify(header["alg"])}`);

    const signed = new TextEncoder().encode(`${headerPart}.${payloadPart}`);
    const signature = base64UrlDecode(signaturePart, "signature");
    let valid = await crypto.subtle.verify(ALGORITHM, await keys.key(options), signature, signed);
    if (!valid && (await keys.refresh(options))) {
      valid = await crypto.subtle.verify(ALGORITHM, await keys.key(options), signature, signed);
    }
    if (!valid) throw new InvalidTokenError("signature is invalid");

    return checkClaims(decodeJson(payloadPart, "payload"), options);
  } catch (err) {
    if (err instanceof InvalidTokenError) throw err;
    if (options.signal?.aborted && err === options.signal.reason) throw err;
    throw new InvalidTokenError((err as Error)?.message ?? String(err), { cause: err });
  }
}

function checkClaims(payload: Record<string, unknown>, options: VerifyOptions): GrantClaims {
  const now = (options.now?.() ?? new Date()).getTime();
  const leeway = options.leewayMs ?? 60_000;

  if (payload["iss"] !== ISSUER) throw new InvalidTokenError(`unexpected issuer ${JSON.stringify(payload["iss"])}`);

  const sub = payload["sub"];
  let subject: string;
  if (typeof sub === "string" && sub) subject = sub;
  else if (typeof sub === "number" && Number.isFinite(sub)) subject = sub.toFixed(0);
  else throw new InvalidTokenError("token has no subject");

  const iat = numericDate(payload, "iat");
  if (iat === undefined) throw new InvalidTokenError("token has no iat");
  if (iat > now + leeway) throw new InvalidTokenError("token was issued in the future");
  const exp = numericDate(payload, "exp");
  if (exp === undefined) throw new InvalidTokenError("token has no exp");
  if (now > exp + leeway) throw new InvalidTokenError("token has expired");
  const nbf = numericDate(payload, "nbf");
  if (nbf !== undefined && now + leeway < nbf) throw new InvalidTokenError("token is not valid yet");
  if (options.maxAgeMs !== undefined && options.maxAgeMs > 0 && now - iat > options.maxAgeMs + leeway) {
    throw new InvalidTokenError("token is too old");
  }

  const aud = payload["aud"];
  let audience: string[] = [];
  if (typeof aud === "string") audience = [aud];
  else if (Array.isArray(aud) && aud.every((a) => typeof a === "string")) audience = aud;
  else if (aud !== undefined) throw new InvalidTokenError("invalid aud claim");
  if (options.audience && !audience.includes(options.audience)) {
    throw new InvalidTokenError("token was issued for a different application");
  }

  const accounts: GrantClaims["accounts"] = {};
  for (const platform of PLATFORMS) {
    const obj = payload[platform];
    if (!isRecord(obj) || typeof obj["identifier"] !== "string" || !obj["identifier"]) continue;
    const account: GrantAccount = { identifier: obj["identifier"] };
    if (typeof obj["name"] === "string" && obj["name"]) account.name = obj["name"];
    if (typeof obj["avatar"] === "string" && obj["avatar"]) account.avatar = obj["avatar"];
    accounts[platform] = account;
  }

  const claims: GrantClaims = { subject, issuedAt: new Date(iat), expiresAt: new Date(exp), audience, accounts };
  return claims;
}

/** A NumericDate claim in epoch milliseconds, or undefined when absent. */
function numericDate(payload: Record<string, unknown>, claim: string): number | undefined {
  const value = payload[claim];
  if (value === undefined) return undefined;
  if (typeof value !== "number" || !Number.isFinite(value)) throw new InvalidTokenError(`invalid ${claim} claim`);
  return value * 1000;
}

function decodeJson(part: string, what: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(base64UrlDecode(part, what)));
  } catch (err) {
    if (err instanceof InvalidTokenError) throw err;
    throw new InvalidTokenError(`malformed ${what}`, { cause: err });
  }
  if (!isRecord(parsed)) throw new InvalidTokenError(`malformed ${what}`);
  return parsed;
}

function base64UrlDecode(value: string, what: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) throw new InvalidTokenError(`malformed ${what}`);
  return base64Decode(value.replace(/-/g, "+").replace(/_/g, "/"));
}

function base64Decode(value: string): Uint8Array<ArrayBuffer> {
  const padded = value + "=".repeat((4 - (value.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

const PEM = /-----BEGIN ([A-Z ]+)-----([A-Za-z0-9+/=\s]+)-----END \1-----/;

/** Imports an RSA public key from a SPKI (`BEGIN PUBLIC KEY`) or PKCS#1 (`BEGIN RSA PUBLIC KEY`) PEM block. */
export async function importPublicKey(pem: string): Promise<CryptoKey> {
  const match = PEM.exec(pem);
  if (!match) throw new InvalidTokenError("no PEM public key found");
  const der = base64Decode(match[2]!.replace(/\s/g, ""));
  let spki: Uint8Array<ArrayBuffer>;
  if (match[1] === "PUBLIC KEY") spki = der;
  else if (match[1] === "RSA PUBLIC KEY") spki = wrapPkcs1(der);
  else throw new InvalidTokenError(`unsupported PEM block "${match[1]}"`);
  try {
    return await crypto.subtle.importKey("spki", spki, ALGORITHM, false, ["verify"]);
  } catch (err) {
    throw new InvalidTokenError("invalid RSA public key", { cause: err });
  }
}

// SubjectPublicKeyInfo ::= SEQUENCE { AlgorithmIdentifier(rsaEncryption, NULL), BIT STRING(pkcs1) }
const RSA_ALGORITHM_IDENTIFIER = [0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00];

function wrapPkcs1(pkcs1: Uint8Array): Uint8Array<ArrayBuffer> {
  const bitString = der(0x03, [0, ...pkcs1]);
  return new Uint8Array(der(0x30, [...RSA_ALGORITHM_IDENTIFIER, ...bitString]));
}

function der(tag: number, content: number[]): number[] {
  const n = content.length;
  const length =
    n < 0x80 ? [n] : n < 0x100 ? [0x81, n] : n < 0x10000 ? [0x82, n >> 8, n & 0xff] : [0x83, n >> 16, (n >> 8) & 0xff, n & 0xff];
  return [tag, ...length, ...content];
}
