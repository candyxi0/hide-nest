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

/** Local V1's formal write boundary: accepted revisions are not implemented or sent. */
export function validateFormalCandidateSetCloseoutInput(raw: unknown) {
  const input = validateCandidateSetCloseoutInput(raw);
  if (
    input.candidates.some(
      (candidate) => candidate.disposition === "ACCEPTED" && candidate.action !== "CREATE",
    )
  ) {
    throw new CandidateSetInputError("accepted candidate action must be CREATE for local v1");
  }
  return input;
}

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
    input = validateFormalCandidateSetCloseoutInput(raw);
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
