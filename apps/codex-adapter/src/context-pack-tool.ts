import { validateContextPackInput, ContextPackInputError } from "./context-pack-input.js";
import { buildContextPackRequest } from "./context-pack-canonicalizer.js";
import {
  loadContextPackConfig,
  retrieveContextPack,
  ContextPackClientError,
} from "./context-pack-client.js";
import type { ContextPackSuccess } from "./context-pack-canonicalizer.js";

/**
 * Orchestrates a single local-private context-pack tool call: validate → config gate → deterministic
 * identity → loopback POST → strict re-check → minimal projection. Only sanitized outcomes leave
 * this module; query, keys, token and base URL are never echoed, and the memory body appears only
 * in the authorized success result.
 */

export interface ContextPackMemoryProjection {
  memoryId: string;
  memoryRevisionId: string;
  revisionNo: number;
  text: string;
  daysAgo: number;
}

export type ContextPackProjection = ContextPackMemoryProjection[];

export type ContextPackToolOutcome =
  | { ok: true; result: ContextPackProjection }
  | { ok: false; text: string };

function sanitizeError(error: unknown): string {
  if (error instanceof ContextPackInputError) {
    return error.message; // already a sanitized rule name
  }
  if (error instanceof ContextPackClientError) {
    if (error.detail) {
      return JSON.stringify(error.detail);
    }
    return error.code;
  }
  return "INTERNAL_FAILURE";
}

/** Projects the validated response down to the minimal shape hide is allowed to receive. */
export function projectContextPack(result: ContextPackSuccess): ContextPackProjection {
  return result.memories.map((memory) => ({
    memoryId: memory.memoryId,
    memoryRevisionId: memory.memoryRevisionId,
    revisionNo: memory.revisionNo,
    text: memory.bodyText,
    daysAgo: memory.evidenceAgeDays,
  }));
}

export async function handleContextPackTool(rawArgs: unknown): Promise<ContextPackToolOutcome> {
  let input;
  try {
    input = validateContextPackInput(rawArgs);
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  let config;
  try {
    config = loadContextPackConfig();
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  let request;
  try {
    request = buildContextPackRequest(input);
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  try {
    const result = await retrieveContextPack(request, config);
    return { ok: true, result: projectContextPack(result) };
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }
}
