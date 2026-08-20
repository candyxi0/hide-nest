import {
  validateMemoryEvidenceInput,
  MemoryEvidenceInputError,
} from "./memory-evidence-input.js";
import {
  buildMemoryEvidenceRequest,
  type MemoryEvidenceSuccess,
  type MemoryEvidenceSegment,
} from "./memory-evidence-canonicalizer.js";
import {
  loadMemoryEvidenceConfig,
  retrieveMemoryEvidence,
  MemoryEvidenceClientError,
} from "./memory-evidence-client.js";

/**
 * Orchestrates a single synthetic memory-evidence tool call: validate → config gate → loopback GET
 * → strict re-check → minimal projection. Only sanitized outcomes leave this module; the input
 * memory ids, token and base URL are never echoed, and the full evidence text appears only in the
 * authorized success result. Internal actor fields are validated upstream but never projected.
 */

export interface MemoryEvidenceProjection {
  status: "EVIDENCE_READY";
  requestId: string;
  memoryId: string;
  memoryRevisionId: string;
  revisionNo: number;
  segmentCount: number;
  messageCount: number;
  evidenceSegments: MemoryEvidenceSegment[];
}

export type MemoryEvidenceToolOutcome =
  | { ok: true; result: MemoryEvidenceProjection }
  | { ok: false; text: string };

function sanitizeError(error: unknown): string {
  if (error instanceof MemoryEvidenceInputError) {
    return error.message; // already a sanitized rule name
  }
  if (error instanceof MemoryEvidenceClientError) {
    if (error.detail) {
      return JSON.stringify(error.detail);
    }
    return error.code;
  }
  return "INTERNAL_FAILURE";
}

/** Projects the validated response down to the minimal shape hide is allowed to receive. */
export function projectMemoryEvidence(result: MemoryEvidenceSuccess): MemoryEvidenceProjection {
  const messageCount = result.segments.reduce((sum, segment) => sum + segment.messages.length, 0);
  return {
    status: "EVIDENCE_READY",
    requestId: result.requestId,
    memoryId: result.memoryId,
    memoryRevisionId: result.currentRevisionId,
    revisionNo: result.revisionNo,
    segmentCount: result.segments.length,
    messageCount,
    evidenceSegments: result.segments,
  };
}

export async function handleMemoryEvidenceTool(rawArgs: unknown): Promise<MemoryEvidenceToolOutcome> {
  let input;
  try {
    input = validateMemoryEvidenceInput(rawArgs);
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  let config;
  try {
    config = loadMemoryEvidenceConfig();
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }

  const request = buildMemoryEvidenceRequest(input);

  try {
    const result = await retrieveMemoryEvidence(request, config);
    return { ok: true, result: projectMemoryEvidence(result) };
  } catch (error) {
    return { ok: false, text: sanitizeError(error) };
  }
}
