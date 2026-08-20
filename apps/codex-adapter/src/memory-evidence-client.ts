import {
  DEFAULT_BASE_URL,
  isHighEntropy,
  validateLoopbackBaseUrl,
} from "./closeout-client.js";
import {
  parseMemoryEvidenceResponse,
  type MemoryEvidenceRequest,
  type MemoryEvidenceSuccess,
} from "./memory-evidence-canonicalizer.js";

/**
 * Loopback-only HTTP transport for `GET /v1/memories/{memoryId}/evidence?revisionId=...`.
 *
 * Reads configuration strictly from process environment (base URL + synthetic bearer only; no
 * capability is needed or sent). It is read-only: only Authorization is sent, and no capability,
 * Cookie or Idempotency-Key is read or transmitted. Never writes secrets to disk, stdout, stderr,
 * exceptions or returned values. Transport failures map to stable sanitized codes; the response is
 * strictly re-validated before any memory body is allowed to leave this module.
 */

const REQUEST_TIMEOUT_MS = 10_000;
const MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5 MiB

export interface MemoryEvidenceConfig {
  baseUrl: string;
  token: string;
  /** Test seam: overrides the fixed 10s request timeout; never read from process environment. */
  timeoutMs?: number;
}

export class MemoryEvidenceClientError extends Error {
  readonly code: string;
  readonly detail?: Record<string, unknown>;
  constructor(code: string, message: string, detail?: Record<string, unknown>) {
    super(message);
    this.name = "MemoryEvidenceClientError";
    this.code = code;
    this.detail = detail;
  }
}

/** Configuration gate: token + loopback base URL must be valid, else fail closed. Capability is not required. */
export function loadMemoryEvidenceConfig(env: NodeJS.ProcessEnv = process.env): MemoryEvidenceConfig {
  const token = env.HIDE_NEST_SYNTHETIC_TOKEN;
  if (!isHighEntropy(token)) {
    throw new MemoryEvidenceClientError(
      "LOCAL_CONFIGURATION_MISSING",
      "LOCAL_CONFIGURATION_MISSING",
    );
  }
  const rawBaseUrl = env.HIDE_NEST_API_BASE_URL ?? DEFAULT_BASE_URL;
  let baseUrl: string;
  try {
    baseUrl = validateLoopbackBaseUrl(rawBaseUrl);
  } catch {
    throw new MemoryEvidenceClientError(
      "LOCAL_CONFIGURATION_MISSING",
      "LOCAL_CONFIGURATION_MISSING",
    );
  }
  return { baseUrl, token };
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

function isTimeoutError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    (error as { name?: unknown }).name === "TimeoutError"
  );
}

function isJsonContentType(value: string | null): boolean {
  if (value === null) return false;
  const mime = value.split(";")[0]?.trim().toLowerCase();
  return mime === "application/json" || mime === "application/problem+json";
}

/** Streams the response body into a buffer while enforcing a real byte cap. Returns null when exceeded. */
async function readCappedBody(response: Response, maxBytes: number): Promise<Buffer | null> {
  const reader = response.body?.getReader();
  if (!reader) return Buffer.alloc(0);
  const chunks: Buffer[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const buffer = Buffer.from(value);
      total += buffer.length;
      if (total > maxBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(buffer);
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks);
}

/**
 * Performs the loopback read-only GET and strictly re-validates the response. The request path uses
 * the already-validated canonical memoryId UUID; the revisionId query value is encodeURIComponent'd.
 * Only HTTP 200 succeeds; any redirect, wrong Content-Type, oversized body, malformed JSON or
 * semantic mismatch fails closed with a stable sanitized code and never returns a partial result.
 */
export async function retrieveMemoryEvidence(
  request: MemoryEvidenceRequest,
  config: MemoryEvidenceConfig,
): Promise<MemoryEvidenceSuccess> {
  const url =
    `${config.baseUrl}/v1/memories/${request.memoryId}/evidence` +
    `?revisionId=${encodeURIComponent(request.memoryRevisionId)}`;

  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${config.token}`,
      },
      redirect: "manual",
      signal: AbortSignal.timeout(config.timeoutMs ?? REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    if (isTimeoutError(error)) {
      throw new MemoryEvidenceClientError("TIMEOUT", "TIMEOUT");
    }
    throw new MemoryEvidenceClientError("NETWORK_FAILURE", "NETWORK_FAILURE");
  }

  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel();
    throw new MemoryEvidenceClientError("REDIRECT_NOT_ALLOWED", "REDIRECT_NOT_ALLOWED");
  }

  if (!isJsonContentType(response.headers.get("content-type"))) {
    await response.body?.cancel();
    throw new MemoryEvidenceClientError("UNSUPPORTED_MEDIA_TYPE", "UNSUPPORTED_MEDIA_TYPE");
  }

  const contentLength = response.headers.get("content-length");
  if (contentLength !== null && Number(contentLength) > MAX_RESPONSE_BYTES) {
    await response.body?.cancel();
    throw new MemoryEvidenceClientError("RESPONSE_TOO_LARGE", "RESPONSE_TOO_LARGE");
  }

  let bytes: Buffer | null;
  try {
    bytes = await readCappedBody(response, MAX_RESPONSE_BYTES);
  } catch (error) {
    // A body-phase abort from the same AbortSignal.timeout is still a timeout, not a disconnect.
    if (isTimeoutError(error)) {
      throw new MemoryEvidenceClientError("TIMEOUT", "TIMEOUT");
    }
    throw new MemoryEvidenceClientError("NETWORK_FAILURE", "NETWORK_FAILURE");
  }
  if (bytes === null) {
    throw new MemoryEvidenceClientError("RESPONSE_TOO_LARGE", "RESPONSE_TOO_LARGE");
  }
  const text = bytes.toString("utf8");

  if (response.status !== 200) {
    throw new MemoryEvidenceClientError(
      "HTTP_FAILURE",
      "HTTP_FAILURE",
      projectProblem(response.status, text),
    );
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new MemoryEvidenceClientError("MALFORMED_JSON", "MALFORMED_JSON");
  }

  try {
    return parseMemoryEvidenceResponse(request, parsed);
  } catch {
    throw new MemoryEvidenceClientError("INVALID_RESPONSE", "INVALID_RESPONSE");
  }
}
