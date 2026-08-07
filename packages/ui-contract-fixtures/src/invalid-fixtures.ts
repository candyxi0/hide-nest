import fixtureManifest from "./fixtures/fixture-manifest.json" with { type: "json" };
import createSessionResponseUnknownField from "./fixtures/invalid/create-session-response-unknown-field.json" with { type: "json" };
import problemDetailUnknownEnum from "./fixtures/invalid/problem-detail-unknown-enum.json" with { type: "json" };
import eventUnknownVersion from "./fixtures/invalid/event-unknown-version.json" with { type: "json" };
import eventForbiddenField from "./fixtures/invalid/event-forbidden-field.json" with { type: "json" };
import eventPurposeOverLimit from "./fixtures/invalid/event-purpose-over-limit.json" with { type: "json" };

export const canonicalInvalidFixtureData = {
  "openapi.create-session.response.unknown-field": createSessionResponseUnknownField,
  "problem.detail.unknown-enum": problemDetailUnknownEnum,
  "event.unknown-version": eventUnknownVersion,
  "event.forbidden-field": eventForbiddenField,
  "event.purpose-known-limit": eventPurposeOverLimit,
} as const;

export const invalidFixtures = fixtureManifest.fixtures
  .filter((entry) => !entry.expectedValid)
  .map((entry) => ({
    ...entry,
    data: canonicalInvalidFixtureData[entry.id as keyof typeof canonicalInvalidFixtureData],
  }));
