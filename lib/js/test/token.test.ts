import { beforeAll, describe, expect, it, vi } from "vitest";
import { InvalidTokenError, KeySource, importPublicKey, isTemporary, verifyGrantToken, type VerifyOptions } from "../src/index.js";
import { NOTCH, fakeApi, genKey, keyServer, reply, sign, toPem, validClaims } from "./helpers.js";

let key: CryptoKeyPair;
let other: CryptoKeyPair;

beforeAll(async () => {
  [key, other] = await Promise.all([genKey(), genKey()]);
});

async function verify(claims: Record<string, unknown>, options: VerifyOptions = {}) {
  const { keys } = await keyServer(key);
  return verifyGrantToken(keys, await sign(key, claims), options);
}

const MINUTE = 60_000;

describe("verifyGrantToken", () => {
  it("returns the claims of a valid token", async () => {
    const claims = await verify(validClaims(new Date()), { maxAgeMs: 10 * MINUTE });
    expect(claims.subject).toBe("1");
    expect(claims.accounts.minecraft).toEqual({ identifier: NOTCH, name: "Notch" });
    expect(claims.accounts.discord).toEqual({ identifier: "1234", name: "notch" });
    expect(claims.audience).toEqual(["my-app"]);
    expect(claims.expiresAt.getTime() - claims.issuedAt.getTime()).toBe(10 * MINUTE);
  });

  it.each([
    ["wrong key", () => sign(other, validClaims(new Date()))],
    ["wrong alg", () => sign(key, validClaims(new Date()), "RS256")],
    ["stale", () => sign(key, validClaims(new Date(Date.now() - 60 * MINUTE)))],
    ["wrong issuer", () => sign(key, { ...validClaims(new Date()), iss: "evil" })],
    ["wrong aud", () => sign(key, { ...validClaims(new Date()), aud: "other-app" })],
    ["garbage", async () => "a.b.c"],
    ["not a JWT", async () => "nope"],
    ["tampered payload", async () => {
      const [h, , s] = (await sign(key, validClaims(new Date()))).split(".");
      const forged = btoa(JSON.stringify({ ...validClaims(new Date()), sub: "2" })).replace(/=+$/, "");
      return `${h}.${forged}.${s}`;
    }],
  ])("rejects %s", async (_, token) => {
    const { keys } = await keyServer(key);
    await expect(verifyGrantToken(keys, await token(), { maxAgeMs: 10 * MINUTE, audience: "my-app" })).rejects.toBeInstanceOf(
      InvalidTokenError,
    );
  });

  it("formats a numeric subject without exponent", async () => {
    const claims = await verify({ ...validClaims(new Date()), sub: 1234567890123 });
    expect(claims.subject).toBe("1234567890123");
  });

  it.each([
    ["no sub", { sub: undefined }],
    ["no iat", { iat: undefined }],
    ["bool sub", { sub: true }],
    ["string iat", { iat: "yesterday" }],
  ])("requires subject and iat (%s)", async (_, override) => {
    await expect(verify({ ...validClaims(new Date()), ...override })).rejects.toBeInstanceOf(InvalidTokenError);
  });

  it("checks expiration", async () => {
    const now = new Date();
    const exp = Math.floor(now.getTime() / 1000) + 3600;
    const claims = await verify({ ...validClaims(now), exp });
    expect(claims.expiresAt?.getTime()).toBe(exp * 1000);

    const expired = { ...validClaims(new Date(now.getTime() - 60 * MINUTE)), exp: Math.floor(now.getTime() / 1000) - 600 };
    await expect(verify(expired)).rejects.toThrow(/expired/);

    await expect(verify({ ...validClaims(now), exp: undefined })).rejects.toThrow(/no exp/);
  });

  it("checks not-before", async () => {
    const now = new Date();
    await expect(verify({ ...validClaims(now), nbf: Math.floor(now.getTime() / 1000) + 600 })).rejects.toThrow(/not valid yet/);
  });

  it.each([
    ["single match", "my-app", "my-app", ["my-app"]],
    ["one of several", ["other", "my-app"], "my-app", ["other", "my-app"]],
    ["aud claim, none checked", ["other", "my-app"], undefined, ["other", "my-app"]],
  ])("audience: %s", async (_, aud, audience, want) => {
    const claims = await verify({ ...validClaims(new Date()), aud }, audience ? { audience } : {});
    expect(claims.audience).toEqual(want);
  });

  it("rejects a token without aud when an audience is set", async () => {
    // it wasn't issued to this application
    await expect(verify({ ...validClaims(new Date()), aud: undefined }, { audience: "my-app" })).rejects.toThrow(/different application/);
  });

  describe("clock", () => {
    const iat = new Date(Date.UTC(2026, 0, 1, 12));
    const at = (ms: number) => () => new Date(iat.getTime() + ms);
    it.each<[string, VerifyOptions, boolean]>([
      ["fresh", { maxAgeMs: 10 * MINUTE, now: at(5 * MINUTE) }, true],
      ["old, within leeway", { maxAgeMs: 10 * MINUTE, now: at(10.5 * MINUTE) }, true],
      ["too old", { maxAgeMs: 10 * MINUTE, now: at(12 * MINUTE) }, false],
      ["too old, custom leeway", { maxAgeMs: 10 * MINUTE, leewayMs: 1000, now: at(10.5 * MINUTE) }, false],
      ["no max age, before exp", { now: at(9 * MINUTE) }, true],
      ["no max age, after exp", { now: at(365 * 24 * 60 * MINUTE) }, false],
      ["issued in the future", { now: at(-5 * MINUTE) }, false],
      ["future, within leeway", { now: at(-0.5 * MINUTE) }, true],
    ])("%s", async (_, options, ok) => {
      const result = verify(validClaims(iat), options);
      if (ok) await expect(result).resolves.toBeDefined();
      else await expect(result).rejects.toBeInstanceOf(InvalidTokenError);
    });
  });

  it("only includes well-formed accounts of known platforms", async () => {
    const claims = await verify({
      ...validClaims(new Date()),
      minecraft: { identifier: NOTCH, name: "Notch", avatar: "https://crafatar.com/x" },
      discord: undefined,
      twitch: { name: "no identifier" },
      patreon: "not an object",
      google: { identifier: "UC123" },
      unknown: { identifier: "ignored" },
    });
    expect(claims.accounts).toEqual({
      minecraft: { identifier: NOTCH, name: "Notch", avatar: "https://crafatar.com/x" },
      google: { identifier: "UC123" },
    });
  });

  it("works without a minecraft account", async () => {
    const claims = await verify({ ...validClaims(new Date()), minecraft: undefined });
    expect(claims.accounts.minecraft).toBeUndefined();
  });
});

describe("KeySource", () => {
  it("requests /rsa", async () => {
    const { keys, requests } = await keyServer(key);
    await keys.key();
    expect(requests[0]!.url.pathname).toBe("/rsa");
    expect(requests[0]!.headers.get("User-Agent")).toBe("Test/1.0");
  });

  it("caches the key across verifications and shares concurrent fetches", async () => {
    const { keys, fetches } = await keyServer(key);
    const token = await sign(key, validClaims(new Date()));
    await Promise.all([verifyGrantToken(keys, token), verifyGrantToken(keys, token), keys.key()]);
    await verifyGrantToken(keys, token);
    expect(fetches()).toBe(1);
  });

  it("refetches when the key rotated", async () => {
    const { keys, fetches } = await keyServer(key, other);
    await keys.key();
    await expect(verifyGrantToken(keys, await sign(other, validClaims(new Date())))).resolves.toBeDefined();
    expect(fetches()).toBe(2);
  });

  it("throttles refetches", async () => {
    const { keys, fetches } = await keyServer(key, other, { refreshIntervalMs: 60 * MINUTE });
    const token = await sign(other, validClaims(new Date()));
    for (let i = 0; i < 3; i++) await expect(verifyGrantToken(keys, token)).rejects.toThrow(/signature/);
    expect(fetches()).toBe(1);
  });

  it("refetches again after the refresh interval", async () => {
    vi.useFakeTimers({ now: 0, toFake: ["Date"] });
    try {
      const { keys, fetches } = await keyServer(key, { refreshIntervalMs: MINUTE });
      await keys.key();
      expect(await keys.refresh()).toBe(false); // throttled
      vi.setSystemTime(61_000);
      expect(await keys.refresh()).toBe(false); // refetched, unchanged
      expect(fetches()).toBe(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("wraps fetch failures, keeping the cause", async () => {
    const { client } = fakeApi(() => reply(503, { error: "down" }));
    const err = await verifyGrantToken(new KeySource(client), await sign(key, validClaims(new Date()))).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(InvalidTokenError);
    expect(isTemporary((err as Error).cause)).toBe(true);
  });

  it("rejects a key that isn't PEM", async () => {
    const { client } = fakeApi(() => new Response("hello", { status: 200 }));
    await expect(new KeySource(client).key()).rejects.toThrow(/no PEM public key/);
  });
});

describe("importPublicKey", () => {
  it("accepts SPKI and PKCS#1 PEM blocks", async () => {
    const spkiPem = await toPem(key.publicKey);
    await expect(importPublicKey(spkiPem)).resolves.toBeDefined();

    // PKCS#1 RSAPublicKey is the BIT STRING inside the SPKI: strip the 24-byte header of a 2048-bit key
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", key.publicKey));
    const pkcs1 = spki.slice(24);
    const pkcs1Pem = `-----BEGIN RSA PUBLIC KEY-----\n${btoa(String.fromCharCode(...pkcs1))}\n-----END RSA PUBLIC KEY-----`;
    const { client } = fakeApi(() => new Response(pkcs1Pem, { status: 200 }));
    await expect(verifyGrantToken(new KeySource(client), await sign(key, validClaims(new Date())))).resolves.toBeDefined();
  });

  it("rejects unsupported blocks", async () => {
    await expect(importPublicKey("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")).rejects.toThrow(/unsupported/);
    await expect(importPublicKey("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----")).rejects.toThrow(/invalid RSA/);
  });
});
