/** Authenticates API requests. */
export interface Credentials {
  /** The Authorization header value, or `undefined` to send none. */
  authorization(): string | undefined;
  /** The built-in credentials print their kind without the secret, so they are safe to log. */
  toString(): string;
}

/** Anonymous access. Only {@link MinecraftAuth.lookup} works without credentials. */
export const anonymous: Credentials = Object.freeze({
  authorization: () => undefined,
  toString: () => "Credentials(anonymous)",
});

/**
 * A creator's server token, sent as `Bearer`.
 * Membership checks answer whether a player follows, subscribes to or supports the token's owner.
 *
 * @deprecated Server tokens were retired: no new ones are issued, and existing ones stop working at the end of the
 * API's v1 grace period. Create an application at https://minecraftauth.me/developers and use
 * {@link application}.
 */
export function serverToken(token: string): Credentials {
  if (!token.trim()) throw new TypeError("Server token must not be blank");
  return { authorization: () => `Bearer ${token}`, toString: () => "Credentials(serverToken, ***)" };
}

/**
 * The legacy `uid:token` form of a server token, sent as `Basic`.
 *
 * @deprecated Server tokens were retired; use {@link application}.
 */
export function serverTokenBasic(uid: string, token: string): Credentials {
  return { authorization: () => basic(uid, token), toString: () => `Credentials(serverTokenBasic, ${uid}, ***)` };
}

/**
 * Application credentials from https://minecraftauth.me/developers. Membership checks made with any of a user's
 * applications answer whether a player follows, subscribes to or supports that user, the application's owner. Also
 * required for access (grant) requests. Keep the secret on your server: never ship it to browsers or anything else
 * distributed to players.
 */
export function application(id: string, secret: string): Credentials {
  if (!id.trim()) throw new TypeError("Application ID must not be blank");
  if (!secret.trim()) throw new TypeError("Application secret must not be blank");
  return { authorization: () => basic(id, secret), toString: () => `Credentials(application, ${id}, ***)` };
}

function basic(user: string, password: string): string {
  const bytes = new TextEncoder().encode(`${user}:${password}`);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return `Basic ${btoa(binary)}`;
}
