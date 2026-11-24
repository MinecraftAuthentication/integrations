import { MinecraftAuth, KeySource, serverToken, type MinecraftAuthOptions } from "../src/index.js";

export const NOTCH = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
export const BASE_URL = "http://api.test";

export interface Recorded {
  method: string;
  url: URL;
  headers: Headers;
  body: string | undefined;
  signal: AbortSignal | undefined;
}

export type Handler = (req: Recorded) => Response | Promise<Response>;

/** A client whose fetch is answered by `handler`, recording every request. */
export function fakeApi(handler: Handler, options: Partial<MinecraftAuthOptions> = {}) {
  const requests: Recorded[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const req: Recorded = {
      method: init?.method ?? "GET",
      url: new URL(String(input)),
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : undefined,
      signal: init?.signal ?? undefined,
    };
    requests.push(req);
    return handler(req);
  };
  const client = new MinecraftAuth({ userAgent: "Test/1.0", credentials: serverToken("tok"), baseUrl: BASE_URL, fetch, ...options });
  return { client, requests };
}

export function reply(status: number, body: unknown = undefined): Response {
  const text = body === undefined ? null : typeof body === "string" ? body : JSON.stringify(body);
  return new Response(text, { status, headers: { "Content-Type": "application/json" } });
}

// ---- grant tokens ----

const RS512 = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-512" } as const;

export async function genKey(): Promise<CryptoKeyPair> {
  return crypto.subtle.generateKey(
    { ...RS512, modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]) },
    true,
    ["sign", "verify"],
  );
}

export async function toPem(key: CryptoKey): Promise<string> {
  const der = new Uint8Array(await crypto.subtle.exportKey("spki", key));
  const b64 = btoa(String.fromCharCode(...der)).replace(/.{64}/g, "$&\n");
  return `-----BEGIN PUBLIC KEY-----\n${b64}\n-----END PUBLIC KEY-----\n`;
}

const b64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const encodeJson = (value: unknown) => b64url(new TextEncoder().encode(JSON.stringify(value)));

/** Signs `claims` with `key`. `alg` "RS256" re-imports the key with SHA-256 to produce a wrong-algorithm token. */
export async function sign(key: CryptoKeyPair, claims: Record<string, unknown>, alg: "RS512" | "RS256" = "RS512"): Promise<string> {
  let privateKey = key.privateKey;
  if (alg === "RS256") {
    const pkcs8 = await crypto.subtle.exportKey("pkcs8", key.privateKey);
    privateKey = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  }
  const signingInput = `${encodeJson({ alg, typ: "JWT" })}.${encodeJson(claims)}`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, new TextEncoder().encode(signingInput));
  return `${signingInput}.${b64url(new Uint8Array(signature))}`;
}

export function validClaims(iat: Date): Record<string, unknown> {
  return {
    sub: "1",
    iss: "MinecraftAuth.me",
    aud: "my-app",
    iat: Math.floor(iat.getTime() / 1000),
    exp: Math.floor(iat.getTime() / 1000) + 600,
    minecraft: { identifier: NOTCH, name: "Notch", avatar: null },
    discord: { identifier: "1234", name: "notch" },
  };
}

/**
 * A KeySource whose /rsa serves each of `keys` in turn (the last one repeatedly). Refreshes aren't throttled
 * unless `refreshIntervalMs` is given.
 */
export async function keyServer(...args: (CryptoKeyPair | { refreshIntervalMs: number })[]) {
  let refreshIntervalMs = 0;
  const keys: CryptoKeyPair[] = [];
  for (const arg of args) {
    if ("refreshIntervalMs" in arg) refreshIntervalMs = arg.refreshIntervalMs;
    else keys.push(arg);
  }
  const pems = await Promise.all(keys.map((k) => toPem(k.publicKey)));
  let fetches = 0;
  const { client, requests } = fakeApi((req) => {
    if (req.url.pathname !== "/rsa") return reply(404, { error: "not found" });
    const pem = pems[Math.min(fetches, pems.length - 1)]!;
    fetches++;
    return new Response(pem, { status: 200, headers: { "Content-Type": "text/plain" } });
  });
  return { keys: new KeySource(client, { refreshIntervalMs }), fetches: () => fetches, requests, client };
}
