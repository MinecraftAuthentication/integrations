import { describe, expect, it } from "vitest";
import { MinecraftAuthError, RateLimitError, isRateLimited, isTemporary } from "../src/index.js";
import { parseRetryAfter } from "../src/errors.js";
import { NOTCH, fakeApi, reply } from "./helpers.js";

function limited(seconds: number): Response {
  return new Response(JSON.stringify({ error: "Rate limit exceeded", status: 429 }), {
    status: 429,
    headers: { "Content-Type": "application/json", "Retry-After": String(seconds) },
  });
}

describe("rate limits", () => {
  it("waits out a short rate limit and retries", async () => {
    let calls = 0;
    const { client, requests } = fakeApi(() => (calls++ === 0 ? limited(1) : reply(200, { result: true })));
    const started = Date.now();
    await expect(client.following(NOTCH, "twitch")).resolves.toBe(true);
    expect(requests).toHaveLength(2);
    expect(Date.now() - started).toBeGreaterThanOrEqual(900);
  });

  it("rejects a long rate limit and holds back later calls", async () => {
    const { client, requests } = fakeApi(() => limited(60));

    const first = await client.following(NOTCH, "twitch").catch((e: unknown) => e);
    expect(first).toBeInstanceOf(RateLimitError);
    expect(first).toBeInstanceOf(MinecraftAuthError);
    expect(isRateLimited(first) && first.retryAfterMs).toBe(60_000);
    expect(isTemporary(first)).toBe(true);
    expect(requests).toHaveLength(1);

    // not sent at all while the limit lasts
    const second = await client.lookup("minecraft", NOTCH).catch((e: unknown) => e);
    expect(isRateLimited(second)).toBe(true);
    expect((second as RateLimitError).retryAfterMs).toBeGreaterThan(0);
    expect((second as RateLimitError).retryAfterMs).toBeLessThanOrEqual(60_000);
    expect(requests).toHaveLength(1);
  });

  it("never waits with maxRateLimitWaitMs 0", async () => {
    const { client, requests } = fakeApi(() => limited(1), { maxRateLimitWaitMs: 0 });
    await expect(client.following(NOTCH, "twitch")).rejects.toBeInstanceOf(RateLimitError);
    expect(requests).toHaveLength(1);
  });

  it("stops waiting when aborted", async () => {
    const { client } = fakeApi(() => limited(5));
    const controller = new AbortController();
    const call = client.following(NOTCH, "twitch", { signal: controller.signal });
    setTimeout(() => controller.abort(new Error("stop")), 50);
    await expect(call).rejects.toThrow("stop");
  });

  it("doesn't treat a 429 without Retry-After as a rate limit", async () => {
    const { client, requests } = fakeApi(() => reply(429, { error: "Too many unanswered access requests", status: 429 }));
    const err = await client.createGrantRequest().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MinecraftAuthError);
    expect(isRateLimited(err)).toBe(false);
    expect(isTemporary(err)).toBe(true);
    expect(requests).toHaveLength(1);
  });

  it("doesn't hold back other calls for the access request limit", async () => {
    const { client, requests } = fakeApi((req) => (req.method === "POST" ? limited(60) : reply(200, { result: true })));
    await expect(client.createGrantRequest()).rejects.toBeInstanceOf(RateLimitError);
    await expect(client.createGrantRequest()).rejects.toBeInstanceOf(RateLimitError);
    await expect(client.following(NOTCH, "twitch")).resolves.toBe(true);
    expect(requests.map((r) => r.method)).toEqual(["POST", "GET"]);
  });

  it("parses Retry-After", () => {
    const now = Date.UTC(2015, 9, 21, 7, 28, 0);
    expect(parseRetryAfter(" 3 ", now)).toBe(3000);
    expect(parseRetryAfter("0", now)).toBe(0);
    expect(parseRetryAfter("Wed, 21 Oct 2015 07:28:30 GMT", now)).toBe(30_000);
    expect(parseRetryAfter("Wed, 21 Oct 2015 07:27:00 GMT", now)).toBe(0);
    for (const value of [null, undefined, "", "-1", "soon"]) expect(parseRetryAfter(value, now)).toBeUndefined();
  });
});
