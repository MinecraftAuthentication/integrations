/**
 * Client for the MinecraftAuth.me v2 API (https://minecraftauth.me/developers).
 *
 * Players link their Minecraft account to Discord, Twitch, Patreon and YouTube on MinecraftAuth.me. This
 * package looks those links up, asks whether a player follows/subscribes to/supports a creator, runs access
 * (grant) requests and verifies the signed grant tokens MinecraftAuth.me issues. It only uses `fetch` and
 * WebCrypto, so it runs on Node 22+, Deno, Bun, edge runtimes and in browsers.
 *
 * @packageDocumentation
 */
export {
  DEFAULT_BASE_URL,
  MinecraftAuth,
  type GrantRequest,
  type GrantRequestStatus,
  type GrantStatus,
  type LinkedAccount,
  type LookupResult,
  type MinecraftAuthOptions,
  type RequestOptions,
} from "./client.js";
export { anonymous, application, serverToken, serverTokenBasic, type Credentials } from "./credentials.js";
export { InvalidTokenError, MinecraftAuthError, isConfig, isNotFound, isTemporary } from "./errors.js";
export { PLATFORMS, parsePlatform, type Platform } from "./platform.js";
export {
  ISSUER,
  KeySource,
  importPublicKey,
  verifyGrantToken,
  type GrantAccount,
  type GrantClaims,
  type KeySourceOptions,
  type VerifyOptions,
} from "./token.js";
