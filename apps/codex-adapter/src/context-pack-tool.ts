import { validateContextPackInput, ContextPackInputError } from "./context-pack-input.js";
import { buildContextPackRequest } from "./context-pack-canonicalizer.js";
import {
  loadContextPackConfig,
  retrieveContextPack,
  ContextPackClientError,
} from "./context-pack-client.js";
import type { ContextPackSuccess } from "./context-pack-canonicalizer.js";

/**
 * Orchestrates a single synthetic context-pack tool call: validate → config gate → deterministic
 * identity → loopback POST → strict re-check → minimal projection. Only sanitized outcomes leave
 * this module; query, keys, token and base URL are never echoed, and the memory body appears only
 * in the authorized success result.
 */

export interface ContextPackProjection {
  status: "CONTEXT_READY" | "NO_RELEVANT_MEMORY";
  resultCategory: ContextPackSuccess["resultCategory"];
  requestId: string;
  deliveryId: string;
  issuedAt: string;
  expiresAt: string;
  budgetLimited: boolean;
  memories: ContextPackSuccess["memories"];
}

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
  return {
    status: result.resultCategory === "SUCCEEDED" ? "CONTEXT_READY" : "NO_RELEVANT_MEMORY",
    resultCategory: result.resultCategory,
    requestId: result.requestId,
    deliveryId: result.deliveryId,
    issuedAt: result.issuedAt,
    expiresAt: result.expiresAt,
    budgetLimited: result.budgetLimited,
    memories: result.memories,
  };
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
