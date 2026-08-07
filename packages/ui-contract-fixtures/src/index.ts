/**
 * UI contract fixtures for hide-nest.
 * Provides valid samples, invalid samples, and canary regression detection.
 * Generated as part of HDM-003 contract-first skeleton.
 */

export { validFixtures } from "./valid-fixtures.js";
export type { FixtureManifestEntry } from "./valid-fixtures.js";

export { invalidFixtures } from "./invalid-fixtures.js";

export { CANARY_HASH, computeCanaryHash } from "./canary.js";
