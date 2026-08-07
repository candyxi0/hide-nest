import { describe, expect, it } from "vitest";
import fixtureManifest from "../fixtures/fixture-manifest.json" with { type: "json" };
import { validFixtures } from "../valid-fixtures.js";
import { invalidFixtures } from "../invalid-fixtures.js";
import { CANARY_HASH, computeCanaryHash } from "../canary.js";

describe("canonical fixture manifest", () => {
  it("exports every manifest entry exactly once", () => {
    expect(validFixtures.length + invalidFixtures.length).toBe(fixtureManifest.fixtures.length);
    expect(new Set([...validFixtures, ...invalidFixtures].map((fixture) => fixture.id)).size).toBe(
      fixtureManifest.fixtures.length,
    );
  });

  it("exports data for every manifest entry", () => {
    for (const fixture of [...validFixtures, ...invalidFixtures]) {
      expect(fixture.schemaRef).toBeTruthy();
      expect(fixture.fixturePath).toBeTruthy();
      expect(fixture.data).toBeDefined();
    }
  });

  it("preserves the valid/invalid split from the manifest", () => {
    expect(validFixtures.every((fixture) => fixture.expectedValid)).toBe(true);
    expect(invalidFixtures.every((fixture) => !fixture.expectedValid)).toBe(true);
  });
});

describe("canonical fixture canary", () => {
  it("uses a fixed SHA-256 value", () => {
    expect(CANARY_HASH).toMatch(/^[0-9a-f]{64}$/);
  });

  it("matches the canonical fixture payload", () => {
    expect(CANARY_HASH).toBe(computeCanaryHash());
  });
});
