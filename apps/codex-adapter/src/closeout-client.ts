import type { CloseoutRequest } from "./closeout-canonicalizer.js";
import { deriveMemoryId } from "./closeout-canonicalizer.js";

/**
 * Loopback-only HTTP transport for the synthetic closeout POST + status GET.
 *
 * Reads configuration strictly from process environment; never writes secrets to disk, stdout,
 * stderr, exceptions or returned values. All failures are sanitized (safe projected fields only).
 */

export const DEFAULT_BASE_URL = "http://127.0.0.1:8080";
const ENTROPY_MIN_LENGTH = 43;
const ENTROPY_MIN_DISTINCT = 16;
const REQUEST_TIMEOUT_MS = 10_000;

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "::1", "[::1]"]);

export interface CloseoutConfig {
  baseUrl: string;
  token: string;
  capability: string;
}

export class CloseoutClientError extends Error {
  readonly code: string;
  readonly detail?: Record<string, unknown>;
  constructor(code: string, message: string, detail?: Record<string, unknown>) {
    super(message);
    this.name = "CloseoutClientError";
    this.code = code;
    this.detail = detail;
  }
}

/** Configuration gate: token/capability/baseUrl must be present and valid, else fail closed. */
export function loadCloseoutConfig(env: NodeJS.ProcessEnv = process.env): CloseoutConfig {
  const token = env.HIDE_NEST_SYNTHETIC_TOKEN;
  const capability = env.HIDE_NEST_SYNTHETIC_CAPABILITY;
  if (!isHighEntropy(token)) {
    throw new CloseoutClientError("LOCAL_CONFIGURATION_MISSING", "LOCAL_CONFIGURATION_MISSING");
  }
  if (!isHighEntropy(capability)) {
    throw new CloseoutClientError("LOCAL_CONFIGURATION_MISSING", "LOCAL_CONFIGURATION_MISSING");
  }

  const rawBaseUrl = env.HIDE_NEST_API_BASE_URL ?? DEFAULT_BASE_URL;
  const baseUrl = validateLoopbackBaseUrl(rawBaseUrl);

  return { baseUrl, token, capability };
}

export function isHighEntropy(value: string | undefined): value is string {
  if (typeof value !== "string" || value.length < ENTROPY_MIN_LENGTH) {
    return false;
  }
  return new Set(value).size >= ENTROPY_MIN_DISTINCT;
}

export function validateLoopbackBaseUrl(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new CloseoutClientError("LOCAL_CONFIGURATION_MISSING", "LOCAL_CONFIGURATION_MISSING");
  }
  const hostname = normalizeHostname(url.hostname);
  if (
    url.protocol !== "http:" ||
    !LOOPBACK_HOSTS.has(hostname) ||
    url.username !== "" ||
    url.password !== "" ||
    (url.pathname !== "/" && url.pathname !== "") ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new CloseoutClientError("LOCAL_CONFIGURATION_MISSING", "LOCAL_CONFIGURATION_MISSING");
  }
  return url.origin;
}

function normalizeHostname(hostname: string): string {
  if (hostname === "[::1]") return "::1";
  return hostname;
}

/**
 * Resolves a relative/absolute statusUrl and enforces same-origin + exact path. The resolved URL
 * must be on the exact same origin as baseUrl, with no userinfo/query/fragment, and its pathname
 * must equal `/v1/runs/{submissionId}`. Any deviation fails closed before any GET, so the
 * Authorization header is never forwarded to another local origin.
 */
function resolveStatusUrl(statusUrl: string, baseUrl: string, submissionId: string): string {
  let resolved: URL;
  try {
    resolved = new URL(statusUrl, baseUrl);
  } catch {
    throw new CloseoutClientError("STATUS_URL_INVALID", "STATUS_URL_INVALID");
  }
  const expectedPath = `/v1/runs/${submissionId}`;
  if (
    resolved.origin !== new URL(baseUrl).origin ||
    resolved.username !== "" ||
    resolved.password !== "" ||
    resolved.search !== "" ||
    resolved.hash !== "" ||
    resolved.pathname !== expectedPath
  ) {
    throw new CloseoutClientError("STATUS_URL_INVALID", "STATUS_URL_INVALID");
  }
  return resolved.toString();
}

/** Projects a known RFC 9457 Problem to only its safe fields; never passes through the raw body. */
function projectProblem(status: number, body: string): Record<string, unknown> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    parsed = undefined;
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    return { status };
  }
  const record = parsed as Record<string, unknown>;
  const safe: Record<string, unknown> = { status };
  if (typeof record.failureCode === "string") safe.failureCode = record.failureCode;
  if (typeof record.resultCategory === "string") safe.resultCategory = record.resultCategory;
  if (typeof record.retryable === "boolean") safe.retryable = record.retryable;
  if (typeof record.requestId === "string") safe.requestId = record.requestId;
  return safe;
}

/**
 * Closed, monotonic closeout phase set. `CANONICAL_COMMITTED` means canonical facts are committed
 * but the vector projection is not yet confirmed; `INDEX_READY` means the frozen-model vector fact
 * for the current revision is also confirmed. The only legal advance is
 * `CANONICAL_COMMITTED` → `INDEX_READY`; any other string or a regression fails closed.
 */
export type CloseoutPhase = "CANONICAL_COMMITTED" | "INDEX_READY";

const PHASE_ORDER: Record<CloseoutPhase, number> = {
  CANONICAL_COMMITTED: 0,
  INDEX_READY: 1,
};

function isCloseoutPhase(value: unknown): value is CloseoutPhase {
  return value === "CANONICAL_COMMITTED" || value === "INDEX_READY";
}

export interface CloseoutSuccess {
  status: "SAVED";
  runId: string;
  memoryId: string;
  phase: CloseoutPhase;
  statusUrl: string;
  selectedEvidenceCount: number;
}

interface PostReceipt {
  runId: string;
  statusUrl: string;
  phase: CloseoutPhase;
  resultCategory: string;
}

interface RunStatus {
  runId: string;
  phase: CloseoutPhase;
  resultCategory: string;
}

async function readText(response: Response): Promise<string> {
  try {
    return await response.text();
  } catch {
    return "";
  }
}

async function postSubmission(
  baseUrl: string,
  token: string,
  capability: string,
  submissionId: string,
  body: string,
): Promise<PostReceipt> {
  let response: Response;
  try {
    response = await fetch(`${baseUrl}/v1/closeout-submissions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "X-Action-Capability": capability,
        "Idempotency-Key": submissionId,
        "Content-Type": "application/json",
      },
      body,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new CloseoutClientError("NETWORK_FAILURE", "NETWORK_FAILURE");
  }

  if (response.status !== 202) {
    const text = await readText(response);
    throw new CloseoutClientError(
      "HTTP_FAILURE",
      "HTTP_FAILURE",
      projectProblem(response.status, text),
    );
  }

  const text = await readText(response);
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new CloseoutClientError("INVALID_RESPONSE", "INVALID_RESPONSE");
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new CloseoutClientError("INVALID_RESPONSE", "INVALID_RESPONSE");
  }
  const record = parsed as Record<string, unknown>;
  if (
    typeof record.runId !== "string" ||
    typeof record.statusUrl !== "string" ||
    typeof record.phase !== "string" ||
    typeof record.resultCategory !== "string"
  ) {
    throw new CloseoutClientError("INVALID_RESPONSE", "INVALID_RESPONSE");
  }
  // R2-01: phase must be a closed value; bind the 202 receipt to this request's facts before any GET.
  const phase = record.phase;
  if (!isCloseoutPhase(phase)) {
    throw new CloseoutClientError("POST_RECEIPT_MISMATCH", "POST_RECEIPT_MISMATCH");
  }
  if (
    record.resultCategory !== "SUCCEEDED" ||
    record.runId !== submissionId ||
    record.statusUrl === ""
  ) {
    throw new CloseoutClientError("POST_RECEIPT_MISMATCH", "POST_RECEIPT_MISMATCH");
  }
  return {
    runId: record.runId,
    statusUrl: record.statusUrl,
    phase,
    resultCategory: record.resultCategory,
  };
}

async function getRunStatus(
  token: string,
  resolvedStatusUrl: string,
  submissionId: string,
  postRunId: string,
  postPhase: CloseoutPhase,
): Promise<RunStatus> {
  let response: Response;
  try {
    response = await fetch(resolvedStatusUrl, {
      method: "GET",
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch {
    throw new CloseoutClientError("CONFIRMATION_PENDING", "CONFIRMATION_PENDING");
  }

  if (response.status !== 200) {
    throw new CloseoutClientError("CONFIRMATION_PENDING", "CONFIRMATION_PENDING");
  }

  const text = await readText(response);
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new CloseoutClientError("CONFIRMATION_PENDING", "CONFIRMATION_PENDING");
  }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new CloseoutClientError("CONFIRMATION_PENDING", "CONFIRMATION_PENDING");
  }
  const record = parsed as Record<string, unknown>;
  if (
    typeof record.runId !== "string" ||
    typeof record.phase !== "string" ||
    typeof record.resultCategory !== "string"
  ) {
    throw new CloseoutClientError("CONFIRMATION_PENDING", "CONFIRMATION_PENDING");
  }
  // R2-01: bind the 200 status to request.submissionId == POST.runId == GET.runId, and require a
  // monotonic closed phase (CANONICAL_COMMITTED may advance to INDEX_READY; INDEX_READY must never
  // regress to CANONICAL_COMMITTED).
  const phase = record.phase;
  if (!isCloseoutPhase(phase)) {
    throw new CloseoutClientError("GET_STATUS_MISMATCH", "GET_STATUS_MISMATCH");
  }
  if (
    record.resultCategory !== "SUCCEEDED" ||
    record.runId !== submissionId ||
    record.runId !== postRunId
  ) {
    throw new CloseoutClientError("GET_STATUS_MISMATCH", "GET_STATUS_MISMATCH");
  }
  if (PHASE_ORDER[postPhase] > PHASE_ORDER[phase]) {
    throw new CloseoutClientError("GET_STATUS_MISMATCH", "GET_STATUS_MISMATCH");
  }
  return { runId: record.runId, phase, resultCategory: record.resultCategory };
}

/**
 * Submits a closed closeout request and performs one GET fact confirmation. Returns the six safe
 * success fields only after POST=202 and GET confirms the same runId with a monotonic closed phase
 * (CANONICAL_COMMITTED or INDEX_READY). The returned phase is the GET-confirmed real phase, never a
 * downgraded value. POST-success-but-unconfirmed is surfaced as a safe-to-replay sanitized failure.
 */
export async function submitCloseout(
  request: CloseoutRequest,
  config: CloseoutConfig,
): Promise<CloseoutSuccess> {
  const receipt = await postSubmission(
    config.baseUrl,
    config.token,
    config.capability,
    request.submissionId,
    JSON.stringify(request),
  );

  // R1-03: enforce same-origin + exact path before any GET.
  const resolvedStatusUrl = resolveStatusUrl(receipt.statusUrl, config.baseUrl, request.submissionId);

  const runStatus = await getRunStatus(
    config.token,
    resolvedStatusUrl,
    request.submissionId,
    receipt.runId,
    receipt.phase,
  );

  return {
    status: "SAVED",
    runId: request.submissionId,
    memoryId: deriveMemoryId(request.submissionId),
    phase: runStatus.phase,
    statusUrl: resolvedStatusUrl,
    selectedEvidenceCount: request.threadReaderManifest.selectedEvidenceMessages.length,
  };
}
