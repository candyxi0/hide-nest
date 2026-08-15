import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createReadStream, existsSync, realpathSync, statSync } from "node:fs";
import { extname, isAbsolute, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import type { Socket } from "node:net";

/**
 * Loopback-only static host + strict /v1 proxy for the Local V1 permanent-deletion console.
 *
 * Every startup input comes only from process arguments or environment variables; none of them
 * are ever written to the frontend bundle, HTML, URL, disk or logs. All configuration is validated
 * fail-closed before the host starts. Uses Node built-ins only.
 */

const ENTROPY_MIN_LENGTH = 43;
const ENTROPY_MIN_DISTINCT = 16;
const MAX_REQUEST_BODY = 64 * 1024; // 64 KiB
const MAX_RESPONSE_BODY = 5 * 1024 * 1024; // 5 MiB
const DEFAULT_TIMEOUT_MS = 10_000;
const CSP = "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'";

const UUID_SEGMENT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const UUID_RE = new RegExp(`^${UUID_SEGMENT}$`, "i");
const RX_MEMORY_DETAIL = new RegExp(`^/v1/memories/${UUID_SEGMENT}$`, "i");
const RX_MEMORY_EVIDENCE = new RegExp(`^/v1/memories/${UUID_SEGMENT}/evidence$`, "i");
const RX_DELETION_RUN = new RegExp(`^/v1/deletion-runs/${UUID_SEGMENT}$`, "i");
const RX_DELETION_CONFIRM = new RegExp(`^/v1/deletion-previews/${UUID_SEGMENT}/confirm$`, "i");

const MIME: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".txt": "text/plain; charset=utf-8",
  ".map": "application/json",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
};

export class ConsoleHostError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ConsoleHostError";
  }
}

export interface ConsoleHostConfig {
  /** Explicit loopback Java API origin: http://127.0.0.1:<port> or http://[::1]:<port>. */
  apiOrigin: string;
  /** Loopback bind host: 127.0.0.1 or ::1. */
  bindHost: string;
  /** Bind port; 0 selects an ephemeral port. */
  port: number;
  /** Absolute path to the static directory (apps/nest-console/dist). */
  staticDir: string;
  /** High-entropy synthetic bearer injected on every proxied API request. */
  bearer: string;
  /** High-entropy synthetic capability injected only on the confirm path. */
  capability: string;
  /** Upstream request timeout in milliseconds (positive integer); defaults to 10000. */
  timeoutMs?: number;
}

export interface ConsoleHost {
  /** Binds the loopback server and resolves the actual bound port. */
  start(): Promise<number>;
  /** Deterministically closes the server, all sockets and any pending upstream reads. */
  close(): Promise<void>;
}

function fail(message: string): never {
  throw new ConsoleHostError(message);
}

function isHighEntropy(value: string): boolean {
  return value.length >= ENTROPY_MIN_LENGTH && new Set(value).size >= ENTROPY_MIN_DISTINCT;
}

export function validateApiOrigin(raw: string): string {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return fail("LOCAL_CONFIGURATION_INVALID: api origin must be an absolute http URL");
  }
  const hostname = url.hostname;
  if (
    url.protocol !== "http:" ||
    (hostname !== "127.0.0.1" && hostname !== "[::1]") ||
    url.username !== "" ||
    url.password !== "" ||
    (url.pathname !== "/" && url.pathname !== "") ||
    url.search !== "" ||
    url.hash !== "" ||
    url.port === ""
  ) {
    return fail("LOCAL_CONFIGURATION_INVALID: api origin must be http://127.0.0.1:<port> or http://[::1]:<port>");
  }
  return url.origin;
}

export function validateBindHost(raw: string): string {
  if (raw === "127.0.0.1" || raw === "::1") {
    return raw;
  }
  return fail("LOCAL_CONFIGURATION_INVALID: bind host must be 127.0.0.1 or ::1");
}

function validatePort(port: number): number {
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    return fail("LOCAL_CONFIGURATION_INVALID: port must be an integer between 0 and 65535");
  }
  return port;
}

function validateTimeoutMs(timeoutMs: number): number {
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {
    return fail("LOCAL_CONFIGURATION_INVALID: upstream timeout must be a positive integer");
  }
  return timeoutMs;
}

function validateStaticDir(dir: string): string {
  const absolute = resolve(dir);
  if (!existsSync(absolute) || !statSync(absolute).isDirectory()) {
    return fail("LOCAL_CONFIGURATION_INVALID: static directory does not exist");
  }
  return absolute;
}

function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}

/** POST request bodies may only be `application/json` (with an optional legal charset parameter). */
function isJsonRequestContentType(value: string | undefined): boolean {
  if (value === undefined) return false;
  const mime = value.split(";")[0]?.trim().toLowerCase();
  return mime === "application/json";
}

/** Upstream responses may be `application/json` or `application/problem+json`. */
function isJsonResponseContentType(value: string | undefined): boolean {
  if (value === undefined) return false;
  const mime = value.split(";")[0]?.trim().toLowerCase();
  return mime === "application/json" || mime === "application/problem+json";
}

/** Detects the abort produced by `AbortSignal.timeout` so timeouts sanitize to a stable 504. */
function isTimeoutError(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { name?: unknown }).name === "TimeoutError";
}

/** Hop-by-hop and browser-credential headers that must never reach the upstream. */
function isForbiddenHeader(name: string): boolean {
  const lower = name.toLowerCase();
  if (
    lower === "authorization" ||
    lower === "cookie" ||
    lower === "x-action-capability" ||
    lower === "forwarded" ||
    lower === "host" ||
    lower === "content-length" ||
    lower === "connection" ||
    lower === "transfer-encoding" ||
    lower === "upgrade" ||
    lower === "keep-alive" ||
    lower === "te" ||
    lower === "trailer"
  ) {
    return true;
  }
  return lower.startsWith("proxy-") || lower.startsWith("x-forwarded-");
}

type ProxyRoute = "read" | "preview" | "confirm";

function matchProxyRoute(method: string, pathname: string): ProxyRoute | null {
  if (method === "GET") {
    if (pathname === "/v1/memories") return "read";
    if (RX_MEMORY_DETAIL.test(pathname)) return "read";
    if (RX_MEMORY_EVIDENCE.test(pathname)) return "read";
    if (RX_DELETION_RUN.test(pathname)) return "read";
    return null;
  }
  if (method === "POST") {
    if (pathname === "/v1/deletion-previews") return "preview";
    if (RX_DELETION_CONFIRM.test(pathname)) return "confirm";
    return null;
  }
  return null;
}

/** Resolve an origin-form pathname to a file inside `root`, rejecting traversal and symlink escape. */
function safeStaticFile(root: string, pathname: string): string | null {
  let decoded: string;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    return null;
  }
  if (decoded.includes("\0")) return null;
  const segments = decoded.split("/").filter((segment) => segment !== "");
  for (const segment of segments) {
    if (segment === "..") return null;
    if (segment.includes("\\")) return null;
  }
  const absolute = resolve(root, ...segments);
  const rel = relative(root, absolute);
  if (rel === ".." || rel.startsWith(".." + sep) || isAbsolute(rel)) {
    return null;
  }
  if (existsSync(absolute)) {
    const realRoot = realpathSync(root);
    const realFile = realpathSync(absolute);
    const realRel = relative(realRoot, realFile);
    if (realRel === ".." || realRel.startsWith(".." + sep) || isAbsolute(realRel)) {
      return null;
    }
  }
  return absolute;
}

async function collectBody(req: IncomingMessage, maxBytes: number): Promise<Buffer | null> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buffer.length;
    if (total > maxBytes) {
      return null;
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}

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

export function createConsoleHost(config: ConsoleHostConfig): ConsoleHost {
  const apiOrigin = validateApiOrigin(config.apiOrigin);
  const bindHost = validateBindHost(config.bindHost);
  const port = validatePort(config.port);
  const staticDir = validateStaticDir(config.staticDir);
  if (!isHighEntropy(config.bearer)) {
    fail("LOCAL_CONFIGURATION_INVALID: synthetic bearer does not meet the entropy floor");
  }
  if (!isHighEntropy(config.capability)) {
    fail("LOCAL_CONFIGURATION_INVALID: synthetic capability does not meet the entropy floor");
  }
  const bearer = config.bearer;
  const capability = config.capability;
  const timeoutMs = validateTimeoutMs(config.timeoutMs ?? DEFAULT_TIMEOUT_MS);

  let boundPort = 0;
  const sockets = new Set<Socket>();

  function applySecurityHeaders(res: ServerResponse): void {
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("Content-Security-Policy", CSP);
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
  }

  function writeProblem(res: ServerResponse, status: number, title: string): void {
    const body = JSON.stringify({ type: "about:blank", title, status });
    res.statusCode = status;
    res.setHeader("Content-Type", "application/problem+json; charset=utf-8");
    res.setHeader("Content-Length", Buffer.byteLength(body));
    res.end(body);
  }

  function writeLocalError(res: ServerResponse, status: number): void {
    writeProblem(res, status, "Request rejected");
  }

  function writeUpstreamError(res: ServerResponse, status: number): void {
    writeProblem(res, status, "Upstream unavailable");
  }

  function hostAllowed(hostHeader: string | undefined): boolean {
    if (hostHeader === undefined) return false;
    const expected = bindHost === "::1" ? `[::1]:${boundPort}` : `${bindHost}:${boundPort}`;
    return hostHeader === expected;
  }

  function originAllowed(originHeader: string | undefined, method: string): boolean {
    if (originHeader === undefined) {
      return method === "GET" || method === "HEAD";
    }
    const expected = bindHost === "::1" ? `http://[::1]:${boundPort}` : `http://${bindHost}:${boundPort}`;
    return originHeader === expected;
  }

  function serveFile(req: IncomingMessage, res: ServerResponse, filePath: string): void {
    const contentType = MIME[extname(filePath).toLowerCase()] ?? "application/octet-stream";
    const stat = statSync(filePath);
    res.statusCode = 200;
    res.setHeader("Content-Type", contentType);
    res.setHeader("Content-Length", stat.size);
    if (req.method === "HEAD") {
      res.end();
      return;
    }
    createReadStream(filePath).pipe(res);
  }

  function handleStatic(req: IncomingMessage, res: ServerResponse, pathname: string): void {
    if (req.method !== "GET" && req.method !== "HEAD") {
      writeLocalError(res, 405);
      return;
    }
    const last = pathname.split("/").pop() ?? "";
    const isPage = extname(last) === "";
    if (isPage) {
      // SPA fallback uses the same realpath root boundary as ordinary assets.
      const indexPath = safeStaticFile(staticDir, "/index.html");
      if (indexPath !== null && existsSync(indexPath)) {
        serveFile(req, res, indexPath);
      } else {
        writeLocalError(res, 404);
      }
      return;
    }
    const filePath = safeStaticFile(staticDir, pathname);
    if (filePath === null || !existsSync(filePath)) {
      writeLocalError(res, 404);
      return;
    }
    serveFile(req, res, filePath);
  }

  async function handleProxy(
    req: IncomingMessage,
    res: ServerResponse,
    pathname: string,
    search: string,
  ): Promise<void> {
    const route = matchProxyRoute(req.method ?? "", pathname);
    if (route === null) {
      writeLocalError(res, 404);
      return;
    }

    const idempotencyKey = req.headers["idempotency-key"];
    if (idempotencyKey !== undefined && !isUuid(String(idempotencyKey))) {
      writeLocalError(res, 400);
      return;
    }

    const headers = new Headers();
    for (const [name, value] of Object.entries(req.headers)) {
      if (value === undefined || isForbiddenHeader(name)) continue;
      headers.set(name, Array.isArray(value) ? value.join(", ") : value);
    }
    headers.set("Authorization", `Bearer ${bearer}`);
    if (route === "confirm") {
      headers.set("X-Action-Capability", capability);
    }

    let body: Buffer;
    if (req.method === "POST") {
      if (!isJsonRequestContentType(req.headers["content-type"])) {
        writeLocalError(res, 415);
        return;
      }
      const collected = await collectBody(req, MAX_REQUEST_BODY);
      if (collected === null) {
        writeLocalError(res, 413);
        req.destroy();
        return;
      }
      body = collected;
      headers.set("Content-Type", String(req.headers["content-type"] ?? "application/json"));
    } else {
      body = Buffer.alloc(0);
    }

    let upstream: Response;
    try {
      upstream = await fetch(apiOrigin + pathname + search, {
        method: req.method,
        headers,
        body: req.method === "POST" ? new Uint8Array(body) : undefined,
        redirect: "manual",
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (error) {
      writeUpstreamError(res, isTimeoutError(error) ? 504 : 502);
      return;
    }

    if (upstream.status >= 300 && upstream.status < 400) {
      await upstream.body?.cancel();
      writeUpstreamError(res, 502);
      return;
    }

    const contentType = upstream.headers.get("content-type") ?? "";
    if (!isJsonResponseContentType(contentType)) {
      await upstream.body?.cancel();
      writeUpstreamError(res, 502);
      return;
    }

    let bytes: Buffer | null;
    try {
      bytes = await readCappedBody(upstream, MAX_RESPONSE_BODY);
    } catch {
      writeUpstreamError(res, 502);
      return;
    }
    if (bytes === null) {
      writeUpstreamError(res, 502);
      return;
    }

    res.statusCode = upstream.status;
    res.setHeader("Content-Type", contentType);
    res.setHeader("Content-Length", bytes.byteLength);
    res.end(bytes);
  }

  async function handleRequest(req: IncomingMessage, res: ServerResponse): Promise<void> {
    try {
      applySecurityHeaders(res);

      if (req.method === "CONNECT" || req.headers.upgrade !== undefined) {
        writeLocalError(res, 405);
        return;
      }
      if (!hostAllowed(req.headers.host)) {
        writeLocalError(res, 403);
        return;
      }
      if (!originAllowed(req.headers.origin, req.method ?? "")) {
        writeLocalError(res, 403);
        return;
      }

      const rawUrl = req.url ?? "/";
      if (!rawUrl.startsWith("/")) {
        writeLocalError(res, 400);
        return;
      }
      let parsed: URL;
      try {
        parsed = new URL(rawUrl, "http://placeholder");
      } catch {
        writeLocalError(res, 400);
        return;
      }
      if (parsed.host !== "placeholder" || parsed.username !== "" || parsed.password !== "") {
        writeLocalError(res, 400);
        return;
      }

      if (parsed.pathname.startsWith("/v1/")) {
        await handleProxy(req, res, parsed.pathname, parsed.search);
      } else {
        handleStatic(req, res, parsed.pathname);
      }
    } catch {
      if (!res.headersSent) {
        writeUpstreamError(res, 500);
      } else {
        res.destroy();
      }
    }
  }

  const server: Server = createServer((req, res) => {
    void handleRequest(req, res);
  });

  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });

  function start(): Promise<number> {
    return new Promise<number>((resolveStart, rejectStart) => {
      const onError = (error: Error) => rejectStart(error);
      server.once("error", onError);
      server.listen(port, bindHost, () => {
        server.removeListener("error", onError);
        const address = server.address();
        boundPort = typeof address === "object" && address !== null ? address.port : port;
        resolveStart(boundPort);
      });
    });
  }

  async function close(): Promise<void> {
    if (!server.listening) return;
    for (const socket of sockets) socket.destroy();
    sockets.clear();
    await new Promise<void>((resolveClose, rejectClose) => {
      server.close((error) => (error ? rejectClose(error) : resolveClose()));
    });
  }

  return { start, close };
}

function parsePort(raw: string | undefined): number {
  if (raw === undefined) return 0;
  const value = Number(raw);
  if (!Number.isInteger(value)) {
    return fail("LOCAL_CONFIGURATION_INVALID: console port must be an integer");
  }
  return value;
}

export function resolveConsoleConfig(env: NodeJS.ProcessEnv = process.env): ConsoleHostConfig {
  const apiOrigin = env.HIDE_NEST_API_BASE_URL;
  const bearer = env.HIDE_NEST_SYNTHETIC_TOKEN;
  const capability = env.HIDE_NEST_SYNTHETIC_CAPABILITY;
  if (apiOrigin === undefined) {
    fail("LOCAL_CONFIGURATION_INVALID: HIDE_NEST_API_BASE_URL is required");
  }
  if (bearer === undefined) {
    fail("LOCAL_CONFIGURATION_INVALID: HIDE_NEST_SYNTHETIC_TOKEN is required");
  }
  if (capability === undefined) {
    fail("LOCAL_CONFIGURATION_INVALID: HIDE_NEST_SYNTHETIC_CAPABILITY is required");
  }
  return {
    apiOrigin,
    bindHost: env.HIDE_NEST_CONSOLE_BIND_HOST ?? "127.0.0.1",
    port: parsePort(env.HIDE_NEST_CONSOLE_PORT),
    staticDir: env.HIDE_NEST_CONSOLE_STATIC_DIR ?? resolve(process.cwd(), "apps/nest-console/dist"),
    bearer,
    capability,
    timeoutMs: env.HIDE_NEST_CONSOLE_TIMEOUT_MS === undefined
      ? undefined
      : validateTimeoutMs(Number(env.HIDE_NEST_CONSOLE_TIMEOUT_MS)),
  };
}

/** Formats the single readiness line; never includes API origin, secrets or absolute paths. */
export function formatReadiness(bindHost: string, port: number): string {
  const hostPart = bindHost === "::1" ? "[::1]" : bindHost;
  return `HIDE_NEST_CONSOLE_READY http://${hostPart}:${port}`;
}

/** Starts the console host and emits exactly one readiness line on the injected writer. */
export async function runConsole(
  env: NodeJS.ProcessEnv = process.env,
  writer: (line: string) => void = (line) => process.stdout.write(line),
): Promise<void> {
  const config = resolveConsoleConfig(env);
  const host = createConsoleHost(config);
  const port = await host.start();
  writer(formatReadiness(config.bindHost, port) + "\n");
  let closing = false;
  const shutdown = (): void => {
    if (closing) return;
    closing = true;
    void host.close().then(
      () => process.exit(0),
      () => process.exit(1),
    );
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  return resolve(entry) === fileURLToPath(import.meta.url);
}

if (isMainModule()) {
  runConsole().catch(() => {
    process.stderr.write("hide-nest-codex-adapter: console host failed to start\n");
    process.exit(1);
  });
}
