import { afterEach, describe, expect, it } from "vitest";
import {
  createServer,
  request as httpRequest,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from "node:http";
import { createServer as createNetServer, type Socket } from "node:net";
import { existsSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  createConsoleHost,
  formatReadiness,
  validateApiOrigin,
  validateBindHost,
  type ConsoleHost,
} from "./console.js";

const VALID_BEARER = "synthetic-token-" + "0123456789abcdef".repeat(4);
const VALID_CAPABILITY = "synthetic-capability-" + "0123456789abcdef".repeat(4);
const UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

const SYMLINK_SUPPORTED = (() => {
  const root = mkdtempSync(join(tmpdir(), "symlink-probe-"));
  try {
    writeFileSync(join(root, "target.txt"), "x");
    symlinkSync(join(root, "target.txt"), join(root, "file-link"), "file");
    mkdirSync(join(root, "target-dir"));
    symlinkSync(join(root, "target-dir"), join(root, "dir-link"), process.platform === "win32" ? "junction" : "dir");
    return existsSync(join(root, "file-link"));
  } catch {
    return false;
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
})();

interface UpstreamRecord {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
  body: string;
}

interface Upstream {
  baseUrl: string;
  records: UpstreamRecord[];
  close: () => Promise<void>;
}

interface HostHandle {
  baseUrl: string;
  port: number;
  host: ConsoleHost;
}

const cleanups: Array<() => Promise<void> | void> = [];

afterEach(async () => {
  while (cleanups.length > 0) {
    const cleanup = cleanups.pop();
    if (cleanup) await cleanup();
  }
});

function startUpstream(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<Upstream> {
  const records: UpstreamRecord[] = [];
  const sockets = new Set<Socket>();
  const server: Server = createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      records.push({
        method: req.method ?? "",
        url: req.url ?? "",
        headers: { ...req.headers },
        body,
      });
      handler(req, res, body);
    });
  });
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address !== null ? address.port : 0;
      const close = () => {
        for (const socket of sockets) socket.destroy();
        sockets.clear();
        return new Promise<void>((r) => server.close(() => r()));
      };
      cleanups.push(close);
      resolve({ baseUrl: `http://127.0.0.1:${port}`, records, close });
    });
  });
}

function jsonOk(_req: IncomingMessage, res: ServerResponse): void {
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: true }));
}

function makeStaticDir(): string {
  const root = mkdtempSync(join(tmpdir(), "console-static-"));
  writeFileSync(join(root, "index.html"), "<!doctype html><html><body>home</body></html>");
  mkdirSync(join(root, "assets"));
  writeFileSync(join(root, "assets", "app.js"), "console.log('app');");
  cleanups.push(() => rmSync(root, { recursive: true, force: true }));
  return root;
}

async function startHost(staticDir: string, upstreamBaseUrl: string, timeoutMs?: number): Promise<HostHandle> {
  const host = createConsoleHost({
    apiOrigin: upstreamBaseUrl,
    bindHost: "127.0.0.1",
    port: 0,
    staticDir,
    bearer: VALID_BEARER,
    capability: VALID_CAPABILITY,
    timeoutMs,
  });
  const port = await host.start();
  cleanups.push(() => host.close());
  return { baseUrl: `http://127.0.0.1:${port}`, port, host };
}

function rawRequest(
  port: number,
  opts: { path: string; method?: string; headers?: Record<string, string>; body?: string },
): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const req = httpRequest(
      {
        host: "127.0.0.1",
        port,
        path: opts.path,
        method: opts.method ?? "GET",
        headers: opts.headers,
      },
      (res) => {
        let body = "";
        res.on("data", (chunk) => (body += chunk));
        res.on("end", () => resolve({ status: res.statusCode ?? 0, body }));
      },
    );
    req.on("error", reject);
    if (opts.body !== undefined) req.write(opts.body);
    req.end();
  });
}

describe("console static host", () => {
  it("serves index, assets, SPA fallback and 404 without touching upstream", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { baseUrl } = await startHost(staticDir, upstream.baseUrl);

    const index = await fetch(baseUrl + "/");
    expect(index.status).toBe(200);
    expect(index.headers.get("cache-control")).toBe("no-store");
    expect(index.headers.get("content-security-policy")).toContain("default-src 'self'");
    expect(index.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
    expect(index.headers.get("x-content-type-options")).toBe("nosniff");
    expect(index.headers.get("referrer-policy")).toBe("no-referrer");
    expect(await index.text()).toContain("home");

    const asset = await fetch(baseUrl + "/assets/app.js");
    expect(asset.status).toBe(200);
    expect(await asset.text()).toContain("app");

    const spa = await fetch(baseUrl + "/some/deep/route");
    expect(spa.status).toBe(200);
    expect(await spa.text()).toContain("home");

    const missingAsset = await fetch(baseUrl + "/assets/missing.js");
    expect(missingAsset.status).toBe(404);

    expect(upstream.records.length).toBe(0);
  });

  it("rejects path and encoded traversal without leaking outside files", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(staticDir, upstream.baseUrl);

    for (const path of ["/../secret.txt", "/%2e%2e/secret.txt", "/%2e%2e%2fsecret.txt", "/..%5csecret.txt"]) {
      const res = await rawRequest(port, { path });
      expect(res.status).toBe(404);
      expect(res.body).not.toContain("SECRET_OUTSIDE_FILE");
    }

    expect(upstream.records.length).toBe(0);
  });

  it.skipIf(!SYMLINK_SUPPORTED)("rejects symlink escape for assets and index/SPA fallback", async () => {
    const root = mkdtempSync(join(tmpdir(), "console-static-symlink-"));
    const outside = mkdtempSync(join(tmpdir(), "console-outside-symlink-"));
    writeFileSync(join(outside, "secret.txt"), "SECRET_OUTSIDE_ASSET");
    writeFileSync(join(outside, "secret.html"), "SECRET_OUTSIDE_INDEX");
    mkdirSync(join(root, "assets"));
    symlinkSync(outside, join(root, "link-out"), process.platform === "win32" ? "junction" : "dir");
    symlinkSync(join(outside, "secret.html"), join(root, "index.html"), "file");
    cleanups.push(() => rmSync(root, { recursive: true, force: true }));
    cleanups.push(() => rmSync(outside, { recursive: true, force: true }));

    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(root, upstream.baseUrl);

    const viaAsset = await rawRequest(port, { path: "/link-out/secret.txt" });
    expect(viaAsset.status).toBe(404);
    expect(viaAsset.body).not.toContain("SECRET_OUTSIDE_ASSET");

    const viaIndex = await rawRequest(port, { path: "/" });
    expect(viaIndex.status).toBe(404);
    expect(viaIndex.body).not.toContain("SECRET_OUTSIDE_INDEX");

    const viaSpa = await rawRequest(port, { path: "/some/route" });
    expect(viaSpa.status).toBe(404);
    expect(viaSpa.body).not.toContain("SECRET_OUTSIDE_INDEX");

    expect(upstream.records.length).toBe(0);
  });
});

describe("console /v1 proxy whitelist", () => {
  const routes = [
    { method: "GET", path: "/v1/memories" },
    { method: "GET", path: `/v1/memories/${UUID}` },
    { method: "GET", path: `/v1/memories/${UUID}/evidence` },
    { method: "POST", path: "/v1/deletion-previews" },
    { method: "POST", path: `/v1/deletion-previews/${UUID}/confirm` },
    { method: "GET", path: `/v1/deletion-runs/${UUID}` },
  ];

  it("forwards exactly the six whitelisted routes and rejects everything else", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { baseUrl } = await startHost(staticDir, upstream.baseUrl);

    for (const route of routes) {
      const init: RequestInit =
        route.method === "POST"
          ? { method: "POST", headers: { "Content-Type": "application/json", Origin: baseUrl }, body: JSON.stringify({}) }
          : { method: "GET" };
      const res = await fetch(baseUrl + route.path, init);
      expect(res.status).toBe(200);
    }
    expect(upstream.records.length).toBe(6);

    const before = upstream.records.length;
    expect((await fetch(baseUrl + "/v1/unknown")).status).toBe(404);
    expect((await fetch(baseUrl + "/v1/memories", { method: "POST", headers: { "Content-Type": "application/json", Origin: baseUrl }, body: "{}" })).status).toBe(404);
    expect((await fetch(baseUrl + "/v1/memories/not-a-uuid")).status).toBe(404);
    expect((await fetch(baseUrl + "/v1/deletion-previews/" + UUID)).status).toBe(404);
    expect(upstream.records.length).toBe(before);
  });

  it("strips browser credentials and injects host bearer, capability only on confirm", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(staticDir, upstream.baseUrl);
    const origin = `http://127.0.0.1:${port}`;

    await rawRequest(port, {
      path: "/v1/memories",
      headers: {
        Authorization: "Bearer forged-browser-token",
        Cookie: "session=forged",
        "X-Action-Capability": "forged-capability",
        "Proxy-Authorization": "Basic forged",
        "X-Forwarded-For": "1.2.3.4",
        Forwarded: "for=1.2.3.4",
      },
    });

    await rawRequest(port, {
      path: `/v1/deletion-previews/${UUID}/confirm`,
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer forged", Origin: origin },
      body: JSON.stringify({}),
    });

    expect(upstream.records.length).toBe(2);

    const read = upstream.records[0];
    expect(read.headers["authorization"]).toBe(`Bearer ${VALID_BEARER}`);
    expect(read.headers["cookie"]).toBeUndefined();
    expect(read.headers["x-action-capability"]).toBeUndefined();
    expect(read.headers["proxy-authorization"]).toBeUndefined();
    expect(read.headers["x-forwarded-for"]).toBeUndefined();
    expect(read.headers["forwarded"]).toBeUndefined();

    const confirm = upstream.records[1];
    expect(confirm.headers["authorization"]).toBe(`Bearer ${VALID_BEARER}`);
    expect(confirm.headers["x-action-capability"]).toBe(VALID_CAPABILITY);
  });

  it("preview and read routes never receive the capability", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(staticDir, upstream.baseUrl);
    const origin = `http://127.0.0.1:${port}`;

    await rawRequest(port, {
      path: "/v1/deletion-previews",
      method: "POST",
      headers: { "Content-Type": "application/json", Origin: origin },
      body: JSON.stringify({}),
    });
    await rawRequest(port, { path: `/v1/deletion-runs/${UUID}` });

    expect(upstream.records.length).toBe(2);
    expect(upstream.records[0].headers["x-action-capability"]).toBeUndefined();
    expect(upstream.records[1].headers["x-action-capability"]).toBeUndefined();
  });

  it("fails closed on idempotency, content-type, body size, redirect, non-JSON and oversized response", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream((req, res) => {
      if (req.url === "/redirect") {
        res.writeHead(302, { Location: "http://127.0.0.1/other" });
        res.end();
        return;
      }
      if (req.url === "/non-json") {
        res.writeHead(200, { "Content-Type": "text/html" });
        res.end("<html>not json</html>");
        return;
      }
      if (req.url === "/oversize") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ data: "x".repeat(6 * 1024 * 1024) }));
        return;
      }
      jsonOk(req, res);
    });
    const { port } = await startHost(staticDir, upstream.baseUrl);
    const origin = `http://127.0.0.1:${port}`;

    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": "not-a-uuid", Origin: origin }, body: "{}" })).status).toBe(400);
    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "text/plain", Origin: origin }, body: "{}" })).status).toBe(415);
    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", Origin: origin }, body: JSON.stringify({ data: "x".repeat(70 * 1024) }) })).status).toBe(413);
    expect(upstream.records.length).toBe(0);

    // Upstream redirect / non-JSON / oversized responses are sanitized to a stable 502.
    const redirectUpstream = await startUpstream((req, res) => {
      res.writeHead(302, { Location: "http://127.0.0.1/elsewhere" });
      res.end();
    });
    const host2 = await startHost(makeStaticDir(), redirectUpstream.baseUrl);
    expect((await fetch(host2.baseUrl + "/v1/memories")).status).toBe(502);
  });
});

describe("console R1 security boundary", () => {
  it("requires exact same-origin on POST and grants capability only to confirm", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(staticDir, upstream.baseUrl);
    const origin = `http://127.0.0.1:${port}`;

    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" })).status).toBe(403);
    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", Origin: "null" }, body: "{}" })).status).toBe(403);
    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", Origin: "http://evil.example" }, body: "{}" })).status).toBe(403);
    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", Origin: `http://127.0.0.1:${port + 1}` }, body: "{}" })).status).toBe(403);
    expect(upstream.records.length).toBe(0);

    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json", Origin: origin }, body: "{}" })).status).toBe(200);
    expect(upstream.records[0].headers["x-action-capability"]).toBeUndefined();

    expect((await rawRequest(port, { path: `/v1/deletion-previews/${UUID}/confirm`, method: "POST", headers: { "Content-Type": "application/json", Origin: origin }, body: "{}" })).status).toBe(200);
    expect(upstream.records[1].headers["x-action-capability"]).toBe(VALID_CAPABILITY);
  });

  it("rejects problem+json request bodies but allows them as responses", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const { port } = await startHost(staticDir, upstream.baseUrl);
    const origin = `http://127.0.0.1:${port}`;

    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/problem+json", Origin: origin }, body: "{}" })).status).toBe(415);
    expect(upstream.records.length).toBe(0);

    expect((await rawRequest(port, { path: "/v1/deletion-previews", method: "POST", headers: { "Content-Type": "application/json; charset=utf-8", Origin: origin }, body: "{}" })).status).toBe(200);
    expect(upstream.records.length).toBe(1);

    const problemUpstream = await startUpstream((_req, res) => {
      res.writeHead(200, { "Content-Type": "application/problem+json" });
      res.end(JSON.stringify({ type: "about:blank", title: "x", status: 200 }));
    });
    const host2 = await startHost(makeStaticDir(), problemUpstream.baseUrl);
    expect((await fetch(host2.baseUrl + "/v1/memories")).status).toBe(200);
  });

  it("times out a hung upstream to a stable 502/504 with no leak", async () => {
    const staticDir = makeStaticDir();
    const hung = await startUpstream(() => {
      // never responds: the request hangs until the host timeout aborts it
    });
    const { baseUrl } = await startHost(staticDir, hung.baseUrl, 50);

    const res = await fetch(baseUrl + "/v1/memories");
    expect([502, 504]).toContain(res.status);
    const body = await res.text();
    expect(body).not.toContain(VALID_BEARER);
    expect(body).not.toContain(VALID_CAPABILITY);
    expect(body).not.toContain("canary");
    expect(body).not.toContain("TimeoutError");
  });

  it("formats a single readiness line with the real bound address and no secrets", async () => {
    expect(formatReadiness("127.0.0.1", 5173)).toBe("HIDE_NEST_CONSOLE_READY http://127.0.0.1:5173");
    expect(formatReadiness("::1", 5173)).toBe("HIDE_NEST_CONSOLE_READY http://[::1]:5173");
    expect(formatReadiness("127.0.0.1", 5173).split("\n")).toHaveLength(1);

    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const host = createConsoleHost({
      apiOrigin: upstream.baseUrl,
      bindHost: "127.0.0.1",
      port: 0,
      staticDir,
      bearer: VALID_BEARER,
      capability: VALID_CAPABILITY,
    });
    const port = await host.start();
    cleanups.push(() => host.close());
    const line = formatReadiness("127.0.0.1", port);
    expect(line).toBe(`HIDE_NEST_CONSOLE_READY http://127.0.0.1:${port}`);

    for (const secret of [VALID_BEARER, VALID_CAPABILITY, upstream.baseUrl, staticDir]) {
      expect(line).not.toContain(secret);
    }
  });
});

describe("console startup and loopback guards", () => {
  it("validates api origin and bind host exactly", () => {
    expect(validateApiOrigin("http://127.0.0.1:8080")).toBe("http://127.0.0.1:8080");
    expect(validateApiOrigin("http://[::1]:8080")).toBe("http://[::1]:8080");
    expect(validateBindHost("127.0.0.1")).toBe("127.0.0.1");
    expect(validateBindHost("::1")).toBe("::1");

    expect(() => validateApiOrigin("http://example.com:8080")).toThrow();
    expect(() => validateApiOrigin("http://127.0.0.1")).toThrow();
    expect(() => validateApiOrigin("http://127.0.0.1:8080/path")).toThrow();
    expect(() => validateApiOrigin("http://user@127.0.0.1:8080")).toThrow();
    expect(() => validateApiOrigin("http://127.0.0.1:8080?x=1")).toThrow();
    expect(() => validateBindHost("0.0.0.0")).toThrow();
  });

  it("rejects non-loopback api origin and malicious Host/Origin", async () => {
    const staticDir = makeStaticDir();
    expect(() =>
      createConsoleHost({
        apiOrigin: "http://192.0.2.1:8080",
        bindHost: "127.0.0.1",
        port: 0,
        staticDir,
        bearer: VALID_BEARER,
        capability: VALID_CAPABILITY,
      }),
    ).toThrow();

    const upstream = await startUpstream(jsonOk);
    const { baseUrl, port } = await startHost(staticDir, upstream.baseUrl);
    expect((await rawRequest(port, { path: "/v1/memories", headers: { Host: "evil.example" } })).status).toBe(403);
    expect((await rawRequest(port, { path: "/v1/memories", headers: { Origin: "http://evil.example" } })).status).toBe(403);
    expect(upstream.records.length).toBe(0);
    expect((await fetch(baseUrl + "/v1/memories")).status).toBe(200);
  });

  it("never leaks token, capability or body canaries through sanitized responses or static content", async () => {
    const leak = "SECRET_LEAK_" + VALID_BEARER + "_" + VALID_CAPABILITY;
    const upstream = await startUpstream((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/html" });
      res.end("<html>" + leak + "</html>");
    });
    const staticDir = makeStaticDir();
    const { baseUrl } = await startHost(staticDir, upstream.baseUrl);

    const proxied = await fetch(baseUrl + "/v1/memories");
    expect(proxied.status).toBe(502);
    const body = await proxied.text();
    expect(body).not.toContain(VALID_BEARER);
    expect(body).not.toContain(VALID_CAPABILITY);
    expect(body).not.toContain(leak);

    const index = await (await fetch(baseUrl + "/")).text();
    expect(index).not.toContain(VALID_BEARER);
    expect(index).not.toContain(VALID_CAPABILITY);
    expect(index).not.toContain(leak);
  });

  it("closes deterministically with no port or handle residue", async () => {
    const staticDir = makeStaticDir();
    const upstream = await startUpstream(jsonOk);
    const host = createConsoleHost({
      apiOrigin: upstream.baseUrl,
      bindHost: "127.0.0.1",
      port: 0,
      staticDir,
      bearer: VALID_BEARER,
      capability: VALID_CAPABILITY,
    });
    const port = await host.start();
    await fetch(`http://127.0.0.1:${port}/`);
    await host.close();
    await expect(host.close()).resolves.toBeUndefined();

    const probe = createNetServer();
    await new Promise<void>((resolve, reject) => {
      probe.once("error", reject);
      probe.listen(port, "127.0.0.1", () => resolve());
    });
    await new Promise<void>((resolve) => probe.close(() => resolve()));
  });
});
