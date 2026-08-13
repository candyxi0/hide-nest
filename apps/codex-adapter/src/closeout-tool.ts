import { buildCloseoutRequest } from "./closeout-canonicalizer.js";
import { validateCloseoutInput, CloseoutInputError } from "./closeout-input.js";
import {
  loadCloseoutConfig,
  submitCloseout,
  CloseoutClientError,
  type CloseoutSuccess,
} from "./closeout-client.js";

/**
 * Orchestrates a single synthetic closeout tool call: validate → config gate → frozen mapping →
 * loopback POST + GET confirmation. Produces only sanitized outcomes; no secret, body text or key
 * is ever echoed in a success or failure result.
 */

export type ToolOutcome =
  | { ok: true; result: CloseoutSuccess }
  | { ok: false; text: string };

function sanitizeError(error: unknown): string {
  if (error instanceof CloseoutInputError) {
    return error.message; // already a sanitized rule name
  }
  if (error instanceof CloseoutClientError) {
    if (error.detail) {
      return JSON.stringify(error.detail);
    }
    return error.code;
  }
  return "INTERNAL_FAILURE";
}

export async function handleCloseoutTool(rawArgs: unknown): Promise<ToolOutcome> {
  let input;
  try {
    input = validateCloseoutInput(rawArgs);
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  let config;
  try {
    config = loadCloseoutConfig();
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  let request;
  try {
    request = buildCloseoutRequest(input);
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  try {
    const result = await submitCloseout(request, config);
    return { ok: true, result };
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }
}
