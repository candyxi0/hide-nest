import { buildCandidateSetCloseoutRequest } from "./candidate-set-closeout-canonicalizer.js";
import {
  CandidateSetClientError,
  loadCandidateSetConfig,
  submitCandidateSet,
  type CandidateSetSuccess,
} from "./candidate-set-closeout-client.js";
import {
  CandidateSetInputError,
  validateCandidateSetCloseoutInput,
} from "./candidate-set-closeout-input.js";

export type CandidateSetToolOutcome =
  | { ok: true; result: CandidateSetSuccess }
  | { ok: false; text: string };

function sanitized(error: unknown): string {
  if (error instanceof CandidateSetInputError) return error.message;
  if (error instanceof CandidateSetClientError) {
    return error.detail === undefined ? error.code : JSON.stringify(error.detail);
  }
  return "INTERNAL_FAILURE";
}

/** Strict input -> deterministic request -> CandidateSet endpoint -> safe response projection. */
export async function handleCandidateSetCloseoutTool(raw: unknown): Promise<CandidateSetToolOutcome> {
  let input;
  try {
    input = validateCandidateSetCloseoutInput(raw);
  } catch (error) {
    return { ok: false, text: sanitized(error) };
  }

  let config;
  try {
    config = loadCandidateSetConfig();
  } catch (error) {
    return { ok: false, text: sanitized(error) };
  }

  let request;
  try {
    request = buildCandidateSetCloseoutRequest(input);
  } catch {
    return { ok: false, text: "INTERNAL_FAILURE" };
  }

  try {
    return { ok: true, result: await submitCandidateSet(request, config) };
  } catch (error) {
    return { ok: false, text: sanitized(error) };
  }
}
