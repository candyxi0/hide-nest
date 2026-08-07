import { createHash } from "node:crypto";
import { canonicalFixtureData } from "./valid-fixtures.js";
import { canonicalInvalidFixtureData } from "./invalid-fixtures.js";

const canonicalFixturePayload = {
  valid: canonicalFixtureData,
  invalid: canonicalInvalidFixtureData,
};

export function computeCanaryHash(): string {
  return createHash("sha256").update(JSON.stringify(canonicalFixturePayload)).digest("hex");
}

// Fixed value generated from the canonical JSON fixture files; this must not be
// assigned by calling computeCanaryHash at module initialization.
export const CANARY_HASH = "d7cf2fa4b0747f38193082d62245feaeb7b8f91ad7e06382581f6f7a0f676426";
