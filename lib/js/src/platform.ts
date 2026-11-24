/** A platform an identity can link an account on. `google` is YouTube; the v2 API calls it "google". */
export type Platform = "minecraft" | "discord" | "twitch" | "patreon" | "google";

/** Every known platform. */
export const PLATFORMS: readonly Platform[] = Object.freeze(["minecraft", "discord", "twitch", "patreon", "google"]);

/** Parses a platform id, ignoring case and surrounding whitespace. "youtube" is accepted as an alias of "google". */
export function parsePlatform(value: string): Platform | undefined {
  const id = value.trim().toLowerCase();
  if (id === "youtube") return "google";
  return PLATFORMS.find((p) => p === id);
}
