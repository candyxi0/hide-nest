/**
 * Local V1 浏览器 QA 共享夹具。
 *
 * 本文件承担两个角色：
 * 1. 作为测试夹具模块被 `read-vertical.spec.ts` 引用（命名导出：合成数据、
 *    路由拦截、审计、axe/截图/存储助手、路径常量）。
 * 2. 作为自定义 Playwright reporter 模块被 `playwright.config.ts` 通过路径引用
 *    （默认导出：MachineResultsReporter），负责把机器结果写入 results.json。
 *
 * 两个角色都在 Node 进程内运行（测试运行器 / reporter 进程），均无浏览器副作用。
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import AxeBuilder from "@axe-core/playwright";
import type { ConsoleMessage, Page, TestInfo } from "@playwright/test";
import type {
  FullResult,
  Reporter,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";

// ---------------------------------------------------------------------------
// 路径常量（唯一事实来源）
// ---------------------------------------------------------------------------
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "..", "..", "..");

export const QA_DIRS = {
  qaDir: path.join(REPO_ROOT, "reports", "local-v1-read-browser-qa"),
  screenshotsDir: path.join(REPO_ROOT, "reports", "local-v1-read-browser-qa", "screenshots"),
  axeDir: path.join(REPO_ROOT, "reports", "local-v1-read-browser-qa", "axe"),
  playwrightReportDir: path.join(REPO_ROOT, "reports", "local-v1-read-browser-qa", "playwright-report"),
};

// ---------------------------------------------------------------------------
// 可切换 fixture 模式
// ---------------------------------------------------------------------------
export type ApiMode =
  | "ok"
  | "empty"
  | "denied-401"
  | "denied-403"
  | "not-found"
  | "invalid"
  | "server-500"
  | "server-503";

const MODE_STATUS: Record<ApiMode, number> = {
  ok: 200,
  empty: 200,
  "denied-401": 401,
  "denied-403": 403,
  "not-found": 404,
  invalid: 422,
  "server-500": 500,
  "server-503": 503,
};

// ---------------------------------------------------------------------------
// 合成数据（不含任何真实记忆 / token / 账号 / 绝对路径）
// ---------------------------------------------------------------------------
export const ACTIVE_MEMORY_ID = "mem-active-0001";
export const ARCHIVED_MEMORY_ID = "mem-archived-0002";

// 超长连续字符：用于验证详情正文不会被裁切也不会横向溢出。
const LONG_CONTINUOUS = "这是一段用于验证超长连续字符不会被裁切也不会横向溢出的排版样本".repeat(8);

export const ACTIVE_DETAIL_BODY = [
  "这是第一条规范记忆的正文首段。它用于验证：在只读详情页里，多段中文能够逐字、完整地呈现，既不会被固定高度截断，也不会被 line-clamp 折叠。",
  "这是第二段正文。它与首段之间由一个空行分隔，用来验证 pre-wrap 换行在三种视口下都保持稳定，段落间距不会因为响应式布局而丢失。",
  "第三段用于承载超长连续字符：" + LONG_CONTINUOUS,
].join("\n\n");

export const ACTIVE_EVIDENCE_ITEMS = [
  {
    anchorId: "evt-active-0001-01",
    sourceUnitId: "unit-active-0001",
    ordinal: 1,
    actorId: "hide:actor-hide-0001",
    actorKind: "ASSISTANT",
    actorStableRef: "hide:actor-hide-0001",
    occurredAt: "2026-07-15T10:31:00.000Z",
    bodyText:
      "我确认一下这条校准记录的边界：纵切只读链路里，浏览器不会持有任何远端凭据，删除围栏在读取路径之外被强制执行。下面这段话用于验证长段文字在证据抽屉中逐字完整呈现，不会被截断，也不会因为行数限制而丢失。当你在三种视口下打开这条完整证据时，应当看到 hide 的消息先出现，随后是小林的消息，最后再回到 hide，顺序必须稳定。",
  },
  {
    anchorId: "evt-active-0001-02",
    sourceUnitId: "unit-active-0001",
    ordinal: 2,
    actorId: "xiaolin:actor-xiaolin-0001",
    actorKind: "HUMAN",
    actorStableRef: "xiaolin:actor-xiaolin-0001",
    occurredAt: "2026-07-15T10:32:30.000Z",
    bodyText:
      "明白。我会在本地手动运行浏览器验收脚本，并只依赖合成数据来验证列表、搜索、筛选、详情与证据抽屉的机械行为。正文、版本与来源边界都来自当前规范事实，页面不会在本地另存一份。这里补一段较长的中文，确保即使消息很长，气泡仍然保持在抽屉的右侧，且不会被固定高度裁断。",
  },
  {
    anchorId: "evt-active-0001-03",
    sourceUnitId: "unit-active-0001",
    ordinal: 3,
    actorId: "hide:actor-hide-0001",
    actorKind: "ASSISTANT",
    actorStableRef: "hide:actor-hide-0001",
    occurredAt: "2026-07-15T10:34:00.000Z",
    bodyText:
      "很好。请记住：证据正文只在抽屉打开时按当前 revision 读取，关闭后立即从页面查询内存中移除；重新打开必须产生一次全新的证据请求，以此证明查询缓存已经被正确清理。这条消息同样是长段文字，用来验证 hide 的第二个气泡仍位于抽屉左半侧，顺序为 hide、小林、hide。",
  },
];

export const SINGLE_EVIDENCE_BODY =
  "这是归档记忆唯一保存的一条最小必要证据，用于验证单消息进入原文展示形态：不出现多消息气泡，而是以 blockquote 原文整段呈现。";

export const ARCHIVED_EVIDENCE_ITEMS = [
  {
    anchorId: "evt-archived-0002-01",
    sourceUnitId: "unit-archived-0002",
    ordinal: 1,
    actorId: "xiaolin:actor-xiaolin-0001",
    actorKind: "HUMAN",
    actorStableRef: "xiaolin:actor-xiaolin-0001",
    occurredAt: "2026-06-01T08:00:00.000Z",
    bodyText: SINGLE_EVIDENCE_BODY,
  },
];

const ACTIVE_LIST_ITEM = {
  memoryId: ACTIVE_MEMORY_ID,
  currentRevisionId: "rev-active-0001-0003",
  revisionNo: 3,
  state: "ACTIVE",
  isolated: false,
  memoryType: "CALIBRATION",
  perspective: "hide:actor-hide-0001",
  title: "关于长期目标的一次校准记录",
  summary: "在七月复盘里，我们重新对齐了纵切项目的边界与验收标准，并把删除围栏列为不可跳过的约束。",
  sourceAvailability: "AVAILABLE",
  uncertaintyCode: "STABLE",
  updatedAt: "2026-07-15T10:30:00.000Z",
};

const ARCHIVED_LIST_ITEM = {
  memoryId: ARCHIVED_MEMORY_ID,
  currentRevisionId: "rev-archived-0002-0001",
  revisionNo: 1,
  state: "ARCHIVED",
  isolated: false,
  memoryType: "QUOTE",
  perspective: "xiaolin:actor-xiaolin-0001",
  title: "一次关于证据边界的引述",
  summary: "归档记录只保留一条最小必要证据，用于验证单消息原文形态。",
  sourceAvailability: "AVAILABLE",
  updatedAt: "2026-06-01T08:00:00.000Z",
};

const ACTIVE_DETAIL = {
  memoryId: ACTIVE_MEMORY_ID,
  currentRevisionId: "rev-active-0001-0003",
  revisionNo: 3,
  state: "ACTIVE",
  memoryType: "CALIBRATION",
  perspectiveActorId: "hide:actor-hide-0001",
  bodyText: ACTIVE_DETAIL_BODY,
  updatedAt: "2026-07-15T10:30:00.000Z",
  evidenceCount: 3,
  uncertaintyCode: "STABLE",
};

const ARCHIVED_DETAIL = {
  memoryId: ARCHIVED_MEMORY_ID,
  currentRevisionId: "rev-archived-0002-0001",
  revisionNo: 1,
  state: "ARCHIVED",
  memoryType: "QUOTE",
  perspectiveActorId: "xiaolin:actor-xiaolin-0001",
  bodyText: "这是归档记忆的正文。它只有一条证据，用来验证单消息进入原文展示形态。",
  updatedAt: "2026-06-01T08:00:00.000Z",
  evidenceCount: 1,
};

// ---------------------------------------------------------------------------
// 响应构造
// ---------------------------------------------------------------------------
function filterList(query: string | null, state: string | null) {
  let items = [ACTIVE_LIST_ITEM, ARCHIVED_LIST_ITEM];
  if (state) items = items.filter((it) => it.state === state);
  if (query && query.trim()) {
    const q = query.trim().toLowerCase();
    items = items.filter(
      (it) => it.title.toLowerCase().includes(q) || it.summary.toLowerCase().includes(q),
    );
  }
  return items;
}

function listPayload(mode: ApiMode, query: string | null, state: string | null) {
  return {
    requestId: "req-synthetic-list",
    resultCategory: "SUCCEEDED",
    items: mode === "empty" ? [] : filterList(query, state),
  };
}

function detailPayload(id: string) {
  if (id === ACTIVE_MEMORY_ID) {
    return { requestId: "req-synthetic-detail", resultCategory: "SUCCEEDED", memory: ACTIVE_DETAIL };
  }
  if (id === ARCHIVED_MEMORY_ID) {
    return { requestId: "req-synthetic-detail", resultCategory: "SUCCEEDED", memory: ARCHIVED_DETAIL };
  }
  return undefined;
}

function evidencePayload(id: string) {
  if (id === ACTIVE_MEMORY_ID) {
    return {
      requestId: "req-synthetic-evidence",
      resultCategory: "SUCCEEDED",
      memoryId: ACTIVE_MEMORY_ID,
      currentRevisionId: ACTIVE_DETAIL.currentRevisionId,
      revisionNo: ACTIVE_DETAIL.revisionNo,
      evidenceItems: ACTIVE_EVIDENCE_ITEMS,
    };
  }
  if (id === ARCHIVED_MEMORY_ID) {
    return {
      requestId: "req-synthetic-evidence",
      resultCategory: "SUCCEEDED",
      memoryId: ARCHIVED_MEMORY_ID,
      currentRevisionId: ARCHIVED_DETAIL.currentRevisionId,
      revisionNo: ARCHIVED_DETAIL.revisionNo,
      evidenceItems: ARCHIVED_EVIDENCE_ITEMS,
    };
  }
  return undefined;
}

function okResponse(body: unknown) {
  return {
    status: 200,
    contentType: "application/json",
    headers: { "Cache-Control": "no-store" },
    body: JSON.stringify(body),
  };
}

const PROBLEM_TITLES: Record<number, string> = {
  401: "未授权",
  403: "禁止访问",
  404: "未找到",
  422: "请求无效",
  500: "服务错误",
  503: "服务不可用",
};

function problemResponse(status: number) {
  return {
    status,
    contentType: "application/problem+json",
    headers: { "Cache-Control": "no-store" },
    body: JSON.stringify({
      type: "about:blank",
      title: PROBLEM_TITLES[status] ?? "错误",
      status,
      detail: "synthetic fixture error",
    }),
  };
}

function isMemoryApi(url: URL): boolean {
  return url.pathname === "/v1/memories" || url.pathname.startsWith("/v1/memories/");
}

/**
 * 将 Chromium 对「本工单主动返回的非 2xx 合成 /v1 响应」产生的网络诊断
 * （`Failed to load resource: the server responded with a status of NNN`）与
 * 应用主动 console.error 区分开。判定必须同时满足：
 *   - console 消息类型为 error；
 *   - 文本命中网络加载失败诊断且携带与当前 mode 预期状态码精确一致的 status；
 *   - 诊断来源 URL 是被拦截的同源 /v1/memories* 端点。
 * 非本类别的 error 一律计入应用 console error。
 */
function isExpectedHttpDiagnostic(msg: ConsoleMessage, mode: ApiMode): boolean {
  if (msg.type() !== "error") return false;
  const expected = MODE_STATUS[mode];
  if (expected < 400) return false;
  const text = msg.text();
  if (!text.includes("Failed to load resource")) return false;
  const statusMatch = text.match(/status of (\d+)/);
  if (!statusMatch || Number(statusMatch[1]) !== expected) return false;
  const locUrl = msg.location().url;
  if (!locUrl) return false;
  let parsed: URL;
  try {
    parsed = new URL(locUrl);
  } catch {
    return false;
  }
  return isMemoryApi(parsed);
}

/** 该 mode 下预期出现的网络诊断数量（仅 4xx/5xx 的列表请求各产生 1 条）。 */
export function expectedHttpDiagnosticCount(mode: ApiMode): number {
  return MODE_STATUS[mode] >= 400 ? 1 : 0;
}

// ---------------------------------------------------------------------------
// 审计
// ---------------------------------------------------------------------------
export interface ApiAudit {
  consoleErrors: number;
  expectedHttpDiagnostics: number;
  pageErrors: number;
  authorizationHeaders: number;
  externalRequests: number;
  evidenceRequests: number;
  writeRequests: number;
  totalRequests: number;
  externalUrls: string[];
}

/**
 * 安装合成 API 路由 + 请求/控制台/pageerror 审计。
 * 任何外部 HTTP(S) 请求都会被计数（测试随后断言其为 0）。
 * Authorization 请求头同样被精确计数（测试断言为 0）。
 */
export async function installSyntheticApi(page: Page, mode: ApiMode): Promise<ApiAudit> {
  const audit: ApiAudit = {
    consoleErrors: 0,
    expectedHttpDiagnostics: 0,
    pageErrors: 0,
    authorizationHeaders: 0,
    externalRequests: 0,
    evidenceRequests: 0,
    writeRequests: 0,
    totalRequests: 0,
    externalUrls: [],
  };

  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    if (isExpectedHttpDiagnostic(msg, mode)) {
      audit.expectedHttpDiagnostics += 1;
    } else {
      audit.consoleErrors += 1;
    }
  });
  // pageerror 同时覆盖未捕获异常与未处理 rejection（Playwright 将二者统一上报为 pageerror）。
  page.on("pageerror", () => {
    audit.pageErrors += 1;
  });
  page.on("request", (req) => {
    audit.totalRequests += 1;
    if (req.method() !== "GET") audit.writeRequests += 1;
    const headers = req.headers();
    if (headers["authorization"]) audit.authorizationHeaders += 1;
    let url: URL;
    try {
      url = new URL(req.url());
    } catch {
      return;
    }
    const host = url.hostname;
    if (
      (url.protocol === "http:" || url.protocol === "https:") &&
      host !== "127.0.0.1" &&
      host !== "localhost"
    ) {
      audit.externalRequests += 1;
      audit.externalUrls.push(req.url());
    }
    if (url.pathname.endsWith("/evidence")) audit.evidenceRequests += 1;
  });

  await page.route(isMemoryApi, async (route) => {
    const url = new URL(route.request().url());
    const status = MODE_STATUS[mode];
    if (status >= 400) {
      await route.fulfill(problemResponse(status));
      return;
    }

    // pathname: /v1/memories 或 /v1/memories/{id} 或 /v1/memories/{id}/evidence
    const segments = url.pathname.split("/").filter(Boolean); // ['v1','memories', ...]
    const id = segments[2];

    if (segments.length === 2) {
      const query = url.searchParams.get("query");
      const state = url.searchParams.get("state");
      await route.fulfill(okResponse(listPayload(mode, query, state)));
      return;
    }
    if (segments.length === 3) {
      const payload = detailPayload(id);
      if (!payload) {
        await route.fulfill(problemResponse(404));
        return;
      }
      await route.fulfill(okResponse(payload));
      return;
    }
    if (segments.length === 4 && segments[3] === "evidence") {
      const payload = evidencePayload(id);
      if (!payload) {
        await route.fulfill(problemResponse(404));
        return;
      }
      await route.fulfill(okResponse(payload));
      return;
    }
    await route.fulfill(problemResponse(404));
  });

  return audit;
}

// ---------------------------------------------------------------------------
// axe / 截图 / 存储 / 浏览器信息 / 机器结果
// ---------------------------------------------------------------------------
export interface AxeScan {
  state: string;
  serious: number;
  critical: number;
  moderate: number;
  minor: number;
  artifactPath: string;
}

export async function runAxe(page: Page, project: string, state: string, index: number): Promise<AxeScan> {
  const results = await new AxeBuilder({ page }).analyze();
  const count = (impact: string) => results.violations.filter((v) => v.impact === impact).length;
  const serious = count("serious");
  const critical = count("critical");
  const moderate = count("moderate");
  const minor = count("minor");

  const absDir = path.join(QA_DIRS.axeDir, project);
  fs.mkdirSync(absDir, { recursive: true });
  const filename = `${String(index).padStart(2, "0")}-${state}.json`;
  fs.writeFileSync(
    path.join(absDir, filename),
    JSON.stringify(
      {
        summary: { state, serious, critical, moderate, minor, violationCount: results.violations.length },
        axeResult: results,
      },
      null,
      2,
    ),
  );

  return { state, serious, critical, moderate, minor, artifactPath: `${project}/${filename}` };
}

export async function captureScreenshot(page: Page, project: string, state: string, index: number): Promise<string> {
  const absDir = path.join(QA_DIRS.screenshotsDir, project);
  fs.mkdirSync(absDir, { recursive: true });
  const filename = `${String(index).padStart(2, "0")}-${state}.png`;
  await page.screenshot({ path: path.join(absDir, filename) });
  return `${project}/${filename}`;
}

export interface StorageAudit {
  localStorage: Record<string, string | null>;
  sessionStorage: Record<string, string | null>;
  indexedDbNames: string[];
  cacheNames: string[];
  serviceWorkers: number;
}

export async function runStorageAudit(page: Page): Promise<StorageAudit> {
  return page.evaluate(async () => {
    const localStorage: Record<string, string | null> = {};
    for (let i = 0; i < window.localStorage.length; i++) {
      const k = window.localStorage.key(i);
      if (k !== null) localStorage[k] = window.localStorage.getItem(k);
    }
    const sessionStorage: Record<string, string | null> = {};
    for (let i = 0; i < window.sessionStorage.length; i++) {
      const k = window.sessionStorage.key(i);
      if (k !== null) sessionStorage[k] = window.sessionStorage.getItem(k);
    }
    let indexedDbNames: string[] = [];
    try {
      indexedDbNames = (await indexedDB.databases()).map((d) => d.name ?? "").filter(Boolean);
    } catch {
      /* ignore */
    }
    let cacheNames: string[] = [];
    try {
      cacheNames = await caches.keys();
    } catch {
      /* ignore */
    }
    let serviceWorkers = 0;
    try {
      serviceWorkers = (await navigator.serviceWorker.getRegistrations()).length;
    } catch {
      /* ignore */
    }
    return { localStorage, sessionStorage, indexedDbNames, cacheNames, serviceWorkers };
  });
}

export async function getBrowserInfo(page: Page) {
  const browser = page.context().browser();
  return {
    name: browser?.browserType().name() ?? "unknown",
    version: browser?.version() ?? "unknown",
  };
}

export function recordMachineResult(testInfo: TestInfo, obj: unknown) {
  testInfo.annotations.push({ type: "machine-results", description: JSON.stringify(obj) });
}

// ---------------------------------------------------------------------------
// 自定义 reporter：聚合机器结果并写入 results.json
// ---------------------------------------------------------------------------
interface TestRecord {
  project: string;
  title: string;
  status: string;
  duration: number;
  error?: string;
}

interface ProjectStatus {
  status: string;
  passed: number;
  failed: number;
  total: number;
  durationMs: number;
}

interface MachineResult {
  kind: string;
  project: string;
  browser?: { name: string; version: string };
  coreFlow?: boolean;
  mode?: string;
  axeScans?: AxeScan[];
  screenshots?: string[];
  requestAudit?: {
    authorizationHeaders: number;
    externalRequests: number;
    evidenceRequests: number;
    writeRequests: number;
    totalRequests: number;
    externalUrls: string[];
  };
  storageAudit?: {
    localStorageKeys: number;
    sessionStorageKeys: number;
    indexedDbNames: number;
    cacheNames: number;
    serviceWorkers: number;
  };
  consoleAudit?: { consoleError: number; pageError: number; expectedHttpDiagnostics?: number };
}

export default class MachineResultsReporter implements Reporter {
  private machineResults: MachineResult[] = [];
  private testRecords: TestRecord[] = [];

  onTestEnd(test: TestCase, result: TestResult) {
    const project = test.parent.project()?.name ?? "unknown";
    this.testRecords.push({
      project,
      title: test.title,
      status: result.status,
      duration: result.duration,
      error: result.error?.message,
    });
    for (const a of result.annotations) {
      if (a.type === "machine-results" && a.description) {
        try {
          this.machineResults.push(JSON.parse(a.description));
        } catch {
          /* ignore malformed annotation */
        }
      }
    }
  }

  onEnd(result: FullResult) {
    const projectNames = Array.from(new Set(this.testRecords.map((t) => t.project)));

    const projects: Record<string, ProjectStatus> = {};
    for (const p of projectNames) {
      const recs = this.testRecords.filter((t) => t.project === p);
      const failed = recs.filter((t) => t.status === "failed" || t.status === "timedOut");
      projects[p] = {
        status: failed.length ? "failed" : "passed",
        passed: recs.filter((t) => t.status === "passed").length,
        failed: failed.length,
        total: recs.length,
        durationMs: recs.reduce((s, t) => s + t.duration, 0),
      };
    }

    const browsers: Record<string, { name: string; version: string }> = {};
    const coreFlow: Record<string, string> = {};
    let axeScans: Array<AxeScan & { project: string }> = [];
    const screenshots: string[] = [];
    let authorizationHeaders = 0;
    let externalRequests = 0;
    let evidenceRequests = 0;
    let writeRequests = 0;
    let totalRequests = 0;
    const externalUrls: string[] = [];
    const consoleAudit = { consoleError: 0, pageError: 0, expectedHttpDiagnostics: 0 };
    const storageSummary = {
      localStorageKeys: 0,
      sessionStorageKeys: 0,
      indexedDbNames: 0,
      cacheNames: 0,
      serviceWorkers: 0,
    };

    for (const m of this.machineResults) {
      if (!m.project) continue;
      if (m.browser) browsers[m.project] = m.browser;
      if (m.coreFlow !== undefined) coreFlow[m.project] = m.coreFlow ? "passed" : "failed";
      if (Array.isArray(m.axeScans)) {
        axeScans = axeScans.concat(m.axeScans.map((s: AxeScan) => ({ ...s, project: m.project })));
      }
      if (Array.isArray(m.screenshots)) screenshots.push(...m.screenshots);
      if (m.requestAudit) {
        authorizationHeaders += m.requestAudit.authorizationHeaders ?? 0;
        externalRequests += m.requestAudit.externalRequests ?? 0;
        evidenceRequests += m.requestAudit.evidenceRequests ?? 0;
        writeRequests += m.requestAudit.writeRequests ?? 0;
        totalRequests += m.requestAudit.totalRequests ?? 0;
        if (Array.isArray(m.requestAudit.externalUrls)) externalUrls.push(...m.requestAudit.externalUrls);
      }
      if (m.storageAudit) {
        storageSummary.localStorageKeys += m.storageAudit.localStorageKeys ?? 0;
        storageSummary.sessionStorageKeys += m.storageAudit.sessionStorageKeys ?? 0;
        storageSummary.indexedDbNames += m.storageAudit.indexedDbNames ?? 0;
        storageSummary.cacheNames += m.storageAudit.cacheNames ?? 0;
        storageSummary.serviceWorkers += m.storageAudit.serviceWorkers ?? 0;
      }
      if (m.consoleAudit) {
        consoleAudit.consoleError += m.consoleAudit.consoleError ?? 0;
        consoleAudit.pageError += m.consoleAudit.pageError ?? 0;
        consoleAudit.expectedHttpDiagnostics += m.consoleAudit.expectedHttpDiagnostics ?? 0;
      }
    }

    const axeTotals = {
      total: axeScans.length,
      serious: axeScans.reduce((s, x) => s + (x.serious ?? 0), 0),
      critical: axeScans.reduce((s, x) => s + (x.critical ?? 0), 0),
      moderate: axeScans.reduce((s, x) => s + (x.moderate ?? 0), 0),
      minor: axeScans.reduce((s, x) => s + (x.minor ?? 0), 0),
      scans: axeScans,
    };

    const failedCriteria = this.testRecords
      .filter((t) => t.status === "failed" || t.status === "timedOut")
      .map((t) => ({ project: t.project, test: t.title, error: t.error ?? "unknown error" }));

    const payload = {
      schemaVersion: 1,
      executedAt: new Date().toISOString(),
      startTime: result.startTime.toISOString(),
      durationMs: result.duration,
      overallStatus: result.status,
      browsers,
      projects,
      coreFlow,
      axeScans: axeTotals,
      requestAudit: {
        authorizationHeaders,
        externalRequests,
        evidenceRequests,
        writeRequests,
        totalRequests,
        externalUrls,
      },
      storageAudit: storageSummary,
      consoleAudit,
      screenshots,
      failedCriteria,
    };

    fs.mkdirSync(QA_DIRS.qaDir, { recursive: true });
    fs.writeFileSync(path.join(QA_DIRS.qaDir, "results.json"), JSON.stringify(payload, null, 2));
  }
}
