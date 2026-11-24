import { afterEach, describe, expect, it, vi } from "vitest";
import {
  DEFAULT_BASE_URL,
  MinecraftAuth,
  MinecraftAuthError,
  anonymous,
  application,
  isConfig,
  isNotFound,
  isTemporary,
  parsePlatform,
  serverToken,
  serverTokenBasic,
  type Credentials,
  type GrantRequest,
} from "../src/index.js";
import { BASE_URL, NOTCH, fakeApi, reply } from "./helpers.js";

afterEach(() => {
  vi.useRealTimers();
});

describe("requests", () => {
  it.each<[string, Credentials, string | null]>([
    ["anonymous", anonymous, null],
    ["server token", serverToken("abc"), "Bearer abc"],
    ["legacy server token", serverTokenBasic("uid", "tok"), `Basic ${btoa("uid:tok")}`],
    // the secret may itself contain a colon
    ["application", application("app", "s3cr:et"), "Basic YXBwOnMzY3I6ZXQ="],
    ["non-ASCII secret is UTF-8", application("app", "pässword"), "Basic YXBwOnDDpHNzd29yZA=="],
  ])("sends the Authorization header for %s", async (_, credentials, want) => {
    const { client, requests } = fakeApi(() => reply(200, { accounts: {} }), { credentials });
    await client.lookup("minecraft", NOTCH);
    expect(requests[0]!.headers.get("Authorization")).toBe(want);
  });

  it("is anonymous without credentials", async () => {
    const headers: Headers[] = [];
    const client = new MinecraftAuth({
      userAgent: "Test/1.0",
      baseUrl: BASE_URL,
      fetch: async (_, init) => {
        headers.push(new Headers(init?.headers));
        return reply(200, { accounts: {} });
      },
    });
    await client.lookup("minecraft", NOTCH);
    expect(headers[0]!.has("Authorization")).toBe(false);
  });

  it("never prints secrets", () => {
    expect(String(serverToken("abc"))).not.toContain("abc");
    expect(String(application("app", "s3cret"))).not.toContain("s3cret");
    expect(String(serverTokenBasic("uid", "tok"))).not.toContain("tok");
  });

  it("rejects blank credentials", () => {
    expect(() => serverToken(" ")).toThrow(TypeError);
    expect(() => application("", "x")).toThrow(TypeError);
    expect(() => application("x", "")).toThrow(TypeError);
  });

  it("requires a User-Agent", () => {
    expect(() => new MinecraftAuth({ userAgent: " " })).toThrow(/userAgent/);
    expect(() => new MinecraftAuth({} as never)).toThrow(/userAgent/);
  });

  it("sends User-Agent, Accept and Content-Type", async () => {
    const { client, requests } = fakeApi(() => reply(200, { result: false, id: "r" }));
    await client.following(NOTCH, "twitch");
    await client.createGrantRequest();
    const [get, post] = requests;
    expect(get!.headers.get("User-Agent")).toBe("Test/1.0");
    expect(get!.headers.get("Accept")).toBe("application/json");
    expect(get!.headers.get("Content-Type")).toBeNull();
    expect(post!.method).toBe("POST");
    expect(post!.headers.get("Content-Type")).toBe("application/json");
  });

  it("defaults to minecraftauth.me and the global fetch", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockResolvedValue(reply(200, { result: true }));
    try {
      const client = new MinecraftAuth({ userAgent: "Test/1.0" });
      expect(client.baseUrl).toBe(DEFAULT_BASE_URL);
      await expect(client.following(NOTCH, "twitch")).resolves.toBe(true);
      expect(String(spy.mock.calls[0]![0])).toMatch(/^https:\/\/minecraftauth\.me\/api\/v2\/following\?/);
    } finally {
      spy.mockRestore();
    }
  });

  it("trims trailing slashes from the base URL", async () => {
    const { client, requests } = fakeApi(() => reply(200, { result: true }), { baseUrl: `${BASE_URL}//` });
    await client.following(NOTCH, "twitch");
    expect(requests[0]!.url.pathname).toBe("/api/v2/following");
  });
});

describe("lookup", () => {
  it("returns the linked accounts", async () => {
    const { client, requests } = fakeApi(() =>
      reply(200, { accounts: { minecraft: { identifier: NOTCH }, twitch: { hint: true } } }),
    );
    const result = await client.lookup("discord", "1234", { include: ["minecraft", "twitch"] });
    expect(result).toEqual({ accounts: { minecraft: { identifier: NOTCH }, twitch: { hint: true } } });
    expect(requests[0]!.url.pathname).toBe("/api/v2/lookup/discord/1234");
    expect(requests[0]!.url.searchParams.get("include")).toBe("minecraft,twitch");
  });

  it("escapes the identifier", async () => {
    const { client, requests } = fakeApi(() => reply(200, { accounts: {} }));
    await client.lookup("twitch", "a/b?c#d e");
    expect(requests[0]!.url.pathname).toBe("/api/v2/lookup/twitch/a%2Fb%3Fc%23d%20e");
    expect(requests[0]!.url.search).toBe("");
  });

  it("defaults missing accounts to an empty object", async () => {
    const { client } = fakeApi(() => reply(200, {}));
    expect(await client.lookup("minecraft", NOTCH)).toEqual({ accounts: {} });
  });

  it("returns null when not found", async () => {
    const { client } = fakeApi(() => reply(404, { error: "no such identity" }));
    expect(await client.lookup("minecraft", NOTCH)).toBeNull();
  });

  it("throws on server errors", async () => {
    const { client } = fakeApi(() => reply(503, "upstream down"));
    const err = await client.lookup("minecraft", NOTCH).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MinecraftAuthError);
    expect((err as MinecraftAuthError).status).toBe(503);
    expect((err as Error).message).toBe("mcauth: HTTP 503: Service Unavailable");
    expect(isTemporary(err)).toBe(true);
  });

  it("rejects invalid JSON", async () => {
    const { client } = fakeApi(() => reply(200, "{nope"));
    await expect(client.lookup("minecraft", NOTCH)).rejects.toThrow(/invalid JSON response/);
  });

  it("rejects oversized responses", async () => {
    const { client } = fakeApi(() => reply(200, `{"accounts":{},"x":"${"a".repeat(1 << 20)}"}`));
    await expect(client.lookup("minecraft", NOTCH)).rejects.toThrow(/larger than/);
  });
});

describe("membership", () => {
  it.each([
    ["following", (c: MinecraftAuth) => c.following(NOTCH, "twitch"), "/api/v2/following", { minecraft: NOTCH, platform: "twitch" }],
    [
      "subscribed",
      (c: MinecraftAuth) => c.subscribed(NOTCH, "discord", "role"),
      "/api/v2/subscribed",
      { minecraft: NOTCH, platform: "discord", data: "role" },
    ],
    [
      "subscribed without data",
      (c: MinecraftAuth) => c.subscribed(NOTCH, "patreon"),
      "/api/v2/subscribed",
      { minecraft: NOTCH, platform: "patreon" },
    ],
    ["discordPresent", (c: MinecraftAuth) => c.discordPresent(NOTCH, "42"), "/api/v2/discord/present", { minecraft: NOTCH, server: "42" }],
    [
      "discordRole by name",
      (c: MinecraftAuth) => c.discordRole(NOTCH, "Mod Team", "42"),
      "/api/v2/subscribed",
      { minecraft: NOTCH, platform: "discord", data: "Mod Team", server: "42" },
    ],
    [
      "discordRole by id",
      (c: MinecraftAuth) => c.discordRole(NOTCH, "123"),
      "/api/v2/subscribed",
      { minecraft: NOTCH, platform: "discord", data: "123" },
    ],
  ])("%s", async (_, call, path, query) => {
    const { client, requests } = fakeApi(() => reply(200, { result: true }));
    await expect(call(client)).resolves.toBe(true);
    expect(requests[0]!.url.pathname).toBe(path);
    expect(Object.fromEntries(requests[0]!.url.searchParams)).toEqual(query);
  });

  it("reports false", async () => {
    const { client } = fakeApi(() => reply(200, { result: false }));
    await expect(client.following(NOTCH, "google")).resolves.toBe(false);
  });

  it("treats 404 as false", async () => {
    const { client } = fakeApi(() => reply(404, { error: "not linked" }));
    await expect(client.subscribed(NOTCH, "twitch", "1")).resolves.toBe(false);
  });

  it("rejects a response without a result", async () => {
    const { client } = fakeApi(() => reply(200, {}));
    const err = await client.following(NOTCH, "twitch").catch((e: unknown) => e);
    expect((err as MinecraftAuthError).status).toBe(502);
  });

  it.each([
    [400, "bad discord role", isConfig],
    [401, "bad token", isConfig],
    [403, "forbidden", isConfig],
    [409, "bot not in server", isTemporary],
    [429, "slow down", isTemporary],
    [500, "boom", isTemporary],
  ])("classifies HTTP %i", async (status, message, classify) => {
    const { client } = fakeApi(() => reply(status, { error: message }));
    const err = await client.following(NOTCH, "twitch").catch((e: unknown) => e);
    expect(classify(err)).toBe(true);
    expect((err as Error).message).toBe(`mcauth: HTTP ${status}: ${message}`);
  });
});

describe("errors", () => {
  it("treats network failures as temporary", async () => {
    const { client } = fakeApi(() => {
      throw new TypeError("fetch failed");
    });
    const err = await client.following(NOTCH, "twitch").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MinecraftAuthError);
    expect((err as MinecraftAuthError).status).toBe(0);
    expect((err as Error).message).toBe("mcauth: GET /api/v2/following: fetch failed");
    expect((err as Error).cause).toBeInstanceOf(TypeError);
    expect(isTemporary(err)).toBe(true);
  });

  it("times out", async () => {
    const { client } = fakeApi(
      (req) =>
        new Promise((_, reject) => req.signal!.addEventListener("abort", () => reject(req.signal!.reason))),
      { timeoutMs: 20 },
    );
    const err = await client.following(NOTCH, "twitch").catch((e: unknown) => e);
    expect((err as Error).message).toMatch(/timed out after 20 ms/);
    expect(isTemporary(err)).toBe(true);
  });

  it("rejects with the caller's abort reason", async () => {
    const { client } = fakeApi(
      (req) => new Promise((_, reject) => req.signal!.addEventListener("abort", () => reject(req.signal!.reason))),
    );
    const controller = new AbortController();
    const pending = client.following(NOTCH, "twitch", { signal: controller.signal });
    controller.abort(new Error("stop"));
    await expect(pending).rejects.toThrow("stop");
  });

  it("classifies only MinecraftAuthErrors", () => {
    for (const fn of [isNotFound, isConfig, isTemporary]) {
      expect(fn(new Error("x"))).toBe(false);
      expect(fn(undefined)).toBe(false);
    }
    expect(isNotFound(new MinecraftAuthError(404, "x"))).toBe(true);
    expect(new MinecraftAuthError(0, "no response").message).toBe("mcauth: no response");
  });
});

describe("grant requests", () => {
  const request: GrantRequest = {
    id: "req",
    code: "ABCD",
    url: "https://minecraftauth.me/grant/req",
    verificationUrl: "https://minecraftauth.me/code",
    scopes: ["minecraft", "discord"],
    persist: true,
    expiresAt: 0,
    interval: 2,
  };

  it("creates a request", async () => {
    const { client, requests } = fakeApi(() => reply(201, request));
    const created = await client.createGrantRequest({ scopes: ["discord"], persist: false });
    expect(created).toEqual(request);
    expect(requests[0]!.url.pathname).toBe("/api/v2/grant-requests");
    expect(JSON.parse(requests[0]!.body!)).toEqual({ scopes: ["discord"], persist: false });
  });

  it("sends an empty scope list and persist by default", async () => {
    const { client, requests } = fakeApi(() => reply(201, request));
    await client.createGrantRequest();
    expect(JSON.parse(requests[0]!.body!)).toEqual({ scopes: [], persist: true });
  });

  it("rejects a created request without an id", async () => {
    const { client } = fakeApi(() => reply(201, {}));
    await expect(client.createGrantRequest()).rejects.toBeInstanceOf(MinecraftAuthError);
  });

  it("surfaces configuration errors", async () => {
    const { client } = fakeApi(() => reply(401, { error: "application credentials required" }));
    const err = await client.createGrantRequest().catch((e: unknown) => e);
    expect(isConfig(err)).toBe(true);
  });

  it.each([
    [{ status: "pending", expiresAt: 5 }, { status: "pending", expiresAt: 5 }],
    [{ status: "accepted", expiresAt: 5, token: "jwt" }, { status: "accepted", expiresAt: 5, token: "jwt" }],
    [{ status: "denied", expiresAt: 5 }, { status: "denied", expiresAt: 5 }],
    [{ status: "expired", expiresAt: 5 }, { status: "expired", expiresAt: 5 }],
    [{ status: "something-new", expiresAt: 5 }, { status: "expired", expiresAt: 5 }],
  ])("polls %j", async (body, want) => {
    const { client, requests } = fakeApi(() => reply(200, body));
    expect(await client.getGrantRequest("a/b")).toEqual(want);
    expect(requests[0]!.url.pathname).toBe("/api/v2/grant-requests/a%2Fb");
  });

  it("awaits until the player answers", async () => {
    vi.useFakeTimers({ now: 0 });
    const statuses = ["pending", "pending", "accepted"];
    const { client, requests } = fakeApi(() =>
      reply(200, { status: statuses.shift(), expiresAt: 60_000, token: "jwt" }),
    );
    const pending = client.awaitGrant(request);
    await vi.advanceTimersByTimeAsync(4_000);
    expect(await pending).toEqual({ status: "accepted", expiresAt: 60_000, token: "jwt" });
    expect(requests).toHaveLength(3);
  });

  it("stops polling once the request would expire", async () => {
    vi.useFakeTimers({ now: 0 });
    const { client, requests } = fakeApi(() => reply(200, { status: "pending", expiresAt: 3_000 }));
    const pending = client.awaitGrant(request);
    await vi.advanceTimersByTimeAsync(2_000);
    expect(await pending).toEqual({ status: "expired", expiresAt: 3_000 });
    expect(requests).toHaveLength(2);
  });

  it("can be aborted while waiting", async () => {
    const { client } = fakeApi(() => reply(200, { status: "pending", expiresAt: Date.now() + 60_000 }));
    const controller = new AbortController();
    const pending = client.awaitGrant(request, { signal: controller.signal });
    setTimeout(() => controller.abort(new Error("gave up")), 10);
    await expect(pending).rejects.toThrow("gave up");
  });
});

describe("parsePlatform", () => {
  it.each([
    ["minecraft", "minecraft"],
    [" Discord ", "discord"],
    ["TWITCH", "twitch"],
    ["patreon", "patreon"],
    ["google", "google"],
    ["YouTube", "google"],
    ["myspace", undefined],
    ["", undefined],
  ])("%j -> %s", (input, want) => {
    expect(parsePlatform(input)).toBe(want);
  });
});
