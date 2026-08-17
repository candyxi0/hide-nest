import {
  deriveCandidateSetReviewSessionId,
  type CandidateAction,
  type CandidateDisposition,
  type CandidateSetCloseoutRequest,
} from "./candidate-set-closeout-canonicalizer.js";
import { DEFAULT_BASE_URL, isHighEntropy, validateLoopbackBaseUrl } from "./closeout-client.js";

const REQUEST_TIMEOUT_MS = 10_000;
const MAX_RESPONSE_BYTES = 1024 * 1024;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export type CandidateSetSubmissionPhase =
  | "NO_CANDIDATES"
  | "DECISIONS_COMMITTED"
  | "CANONICAL_COMMITTED"
  | "INDEX_READY";
export type CandidateSetProjectionPhase =
  | "REJECTED"
  | "DECISIONS_COMMITTED"
  | "CANONICAL_COMMITTED"
  | "INDEX_READY";

export interface CandidateSetSafeItem {
  candidateId: string;
  ordinal: number;
  disposition: CandidateDisposition;
  action: CandidateAction;
  phase: CandidateSetProjectionPhase;
  memoryId?: string;
  memoryRevisionId?: string;
  revisionNo?: number;
}

export interface CandidateSetSuccess {
  status: "SET_SAVED";
  candidateSetId: string;
  reviewSessionId?: string;
  phase: CandidateSetSubmissionPhase;
  resultCategory: "SUCCEEDED" | "NO_RELEVANT_RESULT";
  acceptedCount: number;
  rejectedCount: number;
  candidates: CandidateSetSafeItem[];
}

export interface CandidateSetConfig {
  baseUrl: string;
  token: string;
  /** Test seam only; production configuration never reads a timeout from the environment. */
  timeoutMs?: number;
}

export class CandidateSetClientError extends Error {
  readonly code: string;
  readonly detail?: Record<string, unknown>;

  constructor(code: string, detail?: Record<string, unknown>) {
    super(code);
    this.name = "CandidateSetClientError";
    this.code = code;
    this.detail = detail;
  }
}

/** Reads exactly the CandidateSet endpoint's loopback base URL and bearer configuration. */
export function loadCandidateSetConfig(env: NodeJS.ProcessEnv = process.env): CandidateSetConfig {
  const token = env.HIDE_NEST_SYNTHETIC_TOKEN;
  if (!isHighEntropy(token)) throw new CandidateSetClientError("LOCAL_CONFIGURATION_MISSING");
  let baseUrl: string;
  try {
    baseUrl = validateLoopbackBaseUrl(env.HIDE_NEST_API_BASE_URL ?? DEFAULT_BASE_URL);
  } catch {
    throw new CandidateSetClientError("LOCAL_CONFIGURATION_MISSING");
  }
  return { baseUrl, token };
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasOwn(value: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key);
}

function assertClosed(
  value: Record<string, unknown>,
  required: readonly string[],
  optional: readonly string[] = [],
): void {
  const allowed = new Set([...required, ...optional]);
  if (Object.keys(value).some((key) => !allowed.has(key))) throw new Error("closed schema");
  if (required.some((key) => !hasOwn(value, key))) throw new Error("missing field");
}

function uuid(value: unknown): value is string {
  return typeof value === "string" && UUID_RE.test(value);
}

function noResourceFields(item: Record<string, unknown>): boolean {
  return !hasOwn(item, "memoryId") && !hasOwn(item, "memoryRevisionId") && !hasOwn(item, "revisionNo");
}

function parseItem(
  value: unknown,
  expected: CandidateSetCloseoutRequest["candidates"][number],
): CandidateSetSafeItem {
  if (!isPlainObject(value)) throw new Error("candidate item");
  assertClosed(
    value,
    ["candidateId", "ordinal", "disposition", "action", "phase"],
    ["memoryId", "memoryRevisionId", "revisionNo"],
  );
  if (
    value.candidateId !== expected.candidateId ||
    value.ordinal !== expected.ordinal ||
    value.disposition !== expected.disposition ||
    value.action !== expected.action ||
    !["REJECTED", "DECISIONS_COMMITTED", "CANONICAL_COMMITTED", "INDEX_READY"].includes(
      value.phase as string,
    )
  ) {
    throw new Error("candidate mismatch");
  }

  const phase = value.phase as CandidateSetProjectionPhase;
  const hasAllResources =
    hasOwn(value, "memoryId") &&
    hasOwn(value, "memoryRevisionId") &&
    hasOwn(value, "revisionNo") &&
    uuid(value.memoryId) &&
    uuid(value.memoryRevisionId) &&
    value.revisionNo === 1;
  if (expected.disposition === "REJECTED") {
    if (phase !== "REJECTED" || !noResourceFields(value)) throw new Error("rejected resources");
  } else if (phase === "CANONICAL_COMMITTED" || phase === "INDEX_READY") {
    if (expected.action !== "CREATE" || !hasAllResources) throw new Error("canonical resources");
  } else if (phase === "DECISIONS_COMMITTED") {
    if (!noResourceFields(value)) throw new Error("decision resources");
  } else {
    throw new Error("accepted phase");
  }

  const result: CandidateSetSafeItem = {
    candidateId: expected.candidateId,
    ordinal: expected.ordinal,
    disposition: expected.disposition,
    action: expected.action,
    phase,
  };
  if (hasAllResources) {
    result.memoryId = value.memoryId as string;
    result.memoryRevisionId = value.memoryRevisionId as string;
    result.revisionNo = 1;
  }
  return Object.freeze(result);
}

/** Strictly binds a 202 response to the exact request and frozen CandidateSet phase lattice. */
export function parseCandidateSetResponse(
  request: CandidateSetCloseoutRequest,
  value: unknown,
): CandidateSetSuccess {
  if (!isPlainObject(value)) throw new Error("response object");
  assertClosed(
    value,
    ["requestId", "resultCategory", "candidateSetId", "phase", "candidates"],
    ["reviewSessionId"],
  );
  if (!uuid(value.requestId) || value.candidateSetId !== request.candidateSetId) {
    throw new Error("response identity");
  }
  if (
    !["NO_CANDIDATES", "DECISIONS_COMMITTED", "CANONICAL_COMMITTED", "INDEX_READY"].includes(
      value.phase as string,
    ) ||
    !["SUCCEEDED", "NO_RELEVANT_RESULT"].includes(value.resultCategory as string) ||
    !Array.isArray(value.candidates) ||
    value.candidates.length !== request.candidates.length
  ) {
    throw new Error("response fields");
  }

  const phase = value.phase as CandidateSetSubmissionPhase;
  const items = value.candidates.map((item, index) => parseItem(item, request.candidates[index]));
  if (new Set(items.map((item) => item.candidateId)).size !== items.length) {
    throw new Error("duplicate candidate id");
  }
  if (new Set(items.map((item) => item.ordinal)).size !== items.length) {
    throw new Error("duplicate candidate ordinal");
  }

  const acceptedCount = request.candidates.filter((candidate) => candidate.disposition === "ACCEPTED").length;
  const rejectedCount = request.candidates.length - acceptedCount;
  const expectedReviewSessionId = deriveCandidateSetReviewSessionId(request.candidateSetId);
  if (request.candidates.length === 0) {
    if (
      phase !== "NO_CANDIDATES" ||
      value.resultCategory !== "SUCCEEDED" ||
      hasOwn(value, "reviewSessionId")
    ) {
      throw new Error("empty response");
    }
  } else {
    if (value.reviewSessionId !== expectedReviewSessionId) throw new Error("review identity");
    const hasAcceptedNonCreate = request.candidates.some(
      (candidate) => candidate.disposition === "ACCEPTED" && candidate.action !== "CREATE",
    );
    if (acceptedCount === 0) {
      if (
        phase !== "DECISIONS_COMMITTED" ||
        value.resultCategory !== "NO_RELEVANT_RESULT" ||
        items.some((item) => item.phase !== "REJECTED")
      ) {
        throw new Error("all rejected response");
      }
    } else if (hasAcceptedNonCreate) {
      if (
        phase !== "DECISIONS_COMMITTED" ||
        value.resultCategory !== "SUCCEEDED" ||
        items.some((item) =>
          item.disposition === "ACCEPTED"
            ? item.phase !== "DECISIONS_COMMITTED"
            : item.phase !== "REJECTED",
        )
      ) {
        throw new Error("decision-only response");
      }
    } else {
      if (
        (phase !== "CANONICAL_COMMITTED" && phase !== "INDEX_READY") ||
        value.resultCategory !== "SUCCEEDED"
      ) {
        throw new Error("create projection response");
      }
      const acceptedItems = items.filter((item) => item.disposition === "ACCEPTED");
      if (
        acceptedItems.some((item) =>
          item.phase !== "CANONICAL_COMMITTED" && item.phase !== "INDEX_READY",
        ) ||
        items.some((item) => item.disposition === "REJECTED" && item.phase !== "REJECTED") ||
        (phase === "INDEX_READY" && acceptedItems.some((item) => item.phase !== "INDEX_READY")) ||
        (phase === "CANONICAL_COMMITTED" &&
          acceptedItems.every((item) => item.phase === "INDEX_READY"))
      ) {
        throw new Error("projection reachability");
      }
    }
  }

  const result: CandidateSetSuccess = {
    status: "SET_SAVED",
    candidateSetId: request.candidateSetId,
    phase,
    resultCategory: value.resultCategory as "SUCCEEDED" | "NO_RELEVANT_RESULT",
    acceptedCount,
    rejectedCount,
    candidates: Object.freeze(items) as CandidateSetSafeItem[],
  };
  if (request.candidates.length > 0) result.reviewSessionId = expectedReviewSessionId;
  return Object.freeze(result);
}

function isJsonContentType(value: string | null): boolean {
  if (value === null) return false;
  const mime = value.split(";", 1)[0]?.trim().toLowerCase();
  return mime === "application/json" || mime === "application/problem+json";
}

async function readCapped(response: Response, signal: AbortSignal): Promise<Buffer> {
  const reader = response.body?.getReader();
  if (!reader) return Buffer.alloc(0);
  const chunks: Buffer[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = Buffer.from(value);
      total += chunk.length;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new CandidateSetClientError("INVALID_RESPONSE");
      }
      chunks.push(chunk);
    }
  } catch (error) {
    if (error instanceof CandidateSetClientError) throw error;
    if (signal.aborted) throw new CandidateSetClientError("TIMEOUT");
    throw new CandidateSetClientError("NETWORK_FAILURE");
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks);
}

function safeProblem(status: number, text: string): Record<string, unknown> {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    value = undefined;
  }
  const result: Record<string, unknown> = { status };
  if (!isPlainObject(value)) return result;
  if (typeof value.failureCode === "string") result.failureCode = value.failureCode;
  if (typeof value.resultCategory === "string") result.resultCategory = value.resultCategory;
  if (typeof value.retryable === "boolean") result.retryable = value.retryable;
  if (typeof value.requestId === "string") result.requestId = value.requestId;
  return result;
}

interface ReplaySnapshot {
  phase: CandidateSetSubmissionPhase;
  itemPhases: Map<string, CandidateSetProjectionPhase>;
}
const replaySnapshots = new Map<string, ReplaySnapshot>();

function mayAdvance(previous: string, next: string): boolean {
  return previous === next || (previous === "CANONICAL_COMMITTED" && next === "INDEX_READY");
}

function verifyMonotonicReplay(result: CandidateSetSuccess): void {
  const previous = replaySnapshots.get(result.candidateSetId);
  if (previous) {
    if (!mayAdvance(previous.phase, result.phase)) throw new CandidateSetClientError("INVALID_RESPONSE");
    for (const item of result.candidates) {
      const priorPhase = previous.itemPhases.get(item.candidateId);
      if (priorPhase === undefined || !mayAdvance(priorPhase, item.phase)) {
        throw new CandidateSetClientError("INVALID_RESPONSE");
      }
    }
  }
  replaySnapshots.set(result.candidateSetId, {
    phase: result.phase,
    itemPhases: new Map(result.candidates.map((item) => [item.candidateId, item.phase])),
  });
}

export function resetCandidateSetReplayStateForTests(): void {
  replaySnapshots.clear();
}

/** One loopback POST, strict response verification and safe projection; never reads capability. */
export async function submitCandidateSet(
  request: CandidateSetCloseoutRequest,
  config: CandidateSetConfig,
): Promise<CandidateSetSuccess> {
  const reviewSessionId = deriveCandidateSetReviewSessionId(request.candidateSetId);
  const signal = AbortSignal.timeout(config.timeoutMs ?? REQUEST_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(
      `${config.baseUrl}/v1/review-sessions/${reviewSessionId}/final-submissions`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${config.token}`,
          "Idempotency-Key": request.candidateSetId,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(request),
        redirect: "manual",
        signal,
      },
    );
  } catch {
    if (signal.aborted) throw new CandidateSetClientError("TIMEOUT");
    throw new CandidateSetClientError("NETWORK_FAILURE");
  }

  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel();
    throw new CandidateSetClientError("INVALID_RESPONSE");
  }
  if (!isJsonContentType(response.headers.get("content-type"))) {
    await response.body?.cancel();
    throw new CandidateSetClientError("INVALID_RESPONSE");
  }
  const contentLength = response.headers.get("content-length");
  if (contentLength !== null) {
    const length = Number(contentLength);
    if (!Number.isSafeInteger(length) || length < 0 || length > MAX_RESPONSE_BYTES) {
      await response.body?.cancel();
      throw new CandidateSetClientError("INVALID_RESPONSE");
    }
  }
  const bytes = await readCapped(response, signal);
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new CandidateSetClientError("INVALID_RESPONSE");
  }
  if (response.status !== 202) {
    throw new CandidateSetClientError("HTTP_FAILURE", safeProblem(response.status, text));
  }
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new CandidateSetClientError("INVALID_RESPONSE");
  }
  let result: CandidateSetSuccess;
  try {
    result = parseCandidateSetResponse(request, value);
  } catch {
    throw new CandidateSetClientError("INVALID_RESPONSE");
  }
  verifyMonotonicReplay(result);
  return result;
}
