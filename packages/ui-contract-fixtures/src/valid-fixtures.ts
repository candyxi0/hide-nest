import fixtureManifest from "./fixtures/fixture-manifest.json" with { type: "json" };
import createSessionRequest from "./fixtures/valid/create-session-request.json" with { type: "json" };
import createSessionResponse from "./fixtures/valid/create-session-response.json" with { type: "json" };
import contextPackResponse from "./fixtures/valid/context-pack-response.json" with { type: "json" };
import problemDetail from "./fixtures/valid/problem-detail.json" with { type: "json" };
import eventEnvelope from "./fixtures/valid/event-envelope.json" with { type: "json" };

export type FixtureManifestEntry = (typeof fixtureManifest.fixtures)[number];

export const canonicalFixtureData = {
  "openapi.create-session.request.valid": createSessionRequest,
  "openapi.create-session.response.valid": createSessionResponse,
  "openapi.context-pack.response.valid": contextPackResponse,
  "problem.detail.valid": problemDetail,
  "event.envelope.valid": eventEnvelope,
} as const;

export const validFixtures = fixtureManifest.fixtures
  .filter((entry) => entry.expectedValid)
  .map((entry) => ({
    ...entry,
    data: canonicalFixtureData[entry.id as keyof typeof canonicalFixtureData],
  }));
