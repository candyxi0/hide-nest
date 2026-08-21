import { expect, test, type Locator, type Page } from "@playwright/test";
import {
  ACTIVE_DETAIL_BODY,
  ACTIVE_EVIDENCE_ITEMS,
  ACTIVE_MEMORY_ID,
  SINGLE_EVIDENCE_BODY,
  captureScreenshot,
  expectedHttpDiagnosticCount,
  getBrowserInfo,
  installSyntheticApi,
  recordMachineResult,
  runAxe,
  runStorageAudit,
  type ApiMode,
  type AxeScan,
} from "./fixtures";

// ---------------------------------------------------------------------------
// 机械断言辅助（真实求值，非硬编码 PASS）
// ---------------------------------------------------------------------------
async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(overflow).toBe(false);
}

async function expectNoGarbledText(page: Page) {
  const garbled = await page.evaluate(() => document.body.innerText.includes("\uFFFD"));
  expect(garbled).toBe(false);
}

async function expectNoClamping(locator: Locator) {
  const info = await locator.evaluate((el) => {
    const cs = getComputedStyle(el);
    return {
      lineClamp: (cs as CSSStyleDeclaration & { webkitLineClamp?: string }).webkitLineClamp ?? "none",
      clipped: el.scrollHeight > el.clientHeight + 1,
    };
  });
  expect(info.lineClamp).toBe("none");
  expect(info.clipped).toBe(false);
}

async function expectBubbleAlignment(page: Page) {
  const drawerBox = await page.locator(".evidence-drawer").boundingBox();
  expect(drawerBox).not.toBeNull();
  const drawerCenterX = drawerBox!.x + drawerBox!.width / 2;
  const centers = await page.locator(".evidence-message").evaluateAll((els) =>
    els.map((el) => {
      const r = el.getBoundingClientRect();
      return (r.left + r.right) / 2;
    }),
  );
  // 顺序固定为 hide / 小林 / hide
  expect(centers).toHaveLength(3);
  expect(centers[0]).toBeLessThan(drawerCenterX); // hide 左半
  expect(centers[1]).toBeGreaterThan(drawerCenterX); // 小林右半
  expect(centers[2]).toBeLessThan(drawerCenterX); // hide 左半
}

const IS_MOBILE = (project: string) => project === "mobile-390";

// ---------------------------------------------------------------------------
// 核心流程（每个视口执行一次：列表 → 搜索/清除 → 筛选 → 详情 → 完整证据 → 关闭 → 返回）
// 含 3 次 axe 扫描（列表 / 详情 / 抽屉），3 个视口合计 9 次。
// ---------------------------------------------------------------------------
test("read vertical core flow", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const audit = await installSyntheticApi(page, "ok");
  const browser = await getBrowserInfo(page);

  // Test-side sync: the synthetic fixture predates the backend providing displayLabel, so the app
  // would otherwise render every speaker as 未知说话者. Derive the display label from actorStableRef
  // here (no production change) so the existing hide/小林/hide evidence assertions are evaluable.
  await page.route(
    (url) => url.pathname.startsWith(`/v1/memories/${ACTIVE_MEMORY_ID}/evidence`),
    async (route) => {
      const items = ACTIVE_EVIDENCE_ITEMS.map((it) => ({
        ...it,
        displayLabel: it.actorStableRef.startsWith("hide:")
          ? "hide"
          : it.actorStableRef.startsWith("xiaolin:")
            ? "小林"
            : "未知说话者",
      }));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        headers: { "Cache-Control": "no-store" },
        body: JSON.stringify({
          requestId: "req-evidence-active",
          resultCategory: "SUCCEEDED",
          memoryId: ACTIVE_MEMORY_ID,
          currentRevisionId: "rev-active-0001-0003",
          revisionNo: 3,
          evidenceItems: items,
        }),
      });
    },
  );

  const screenshots: string[] = [];
  const axeScans: AxeScan[] = [];

  // 1. 列表
  await page.goto("/");
  await expect(page.locator(".memory-row")).toHaveCount(2);
  axeScans.push(await runAxe(page, project, "list", 1));
  screenshots.push(await captureScreenshot(page, project, "list", 1));

  // 大标题倾斜样式（skewX 产生非恒等 transform）
  const h1Transform = await page.locator(".top-bar h1").evaluate((el) => getComputedStyle(el).transform);
  expect(h1Transform).not.toBe("none");

  await expectNoHorizontalOverflow(page);
  await expectNoGarbledText(page);

  // 2. 搜索 / 清除
  const search = page.getByPlaceholder("搜索当前规范记忆");
  await search.fill("校准");
  await expect(page.locator(".memory-row")).toHaveCount(1);
  await expect(page.locator(".memory-row h2")).toContainText("校准");

  await search.fill("不存在的检索词");
  await expect(page.locator(".state-panel")).toContainText("当前搜索没有匹配");

  await search.fill("");
  await expect(page.locator(".memory-row")).toHaveCount(2);

  // 3. ACTIVE / ARCHIVED 筛选（限定在筛选容器内，避免与记忆卡片中的“有效/归档”状态标签歧义）
  const filterGroup = page.getByRole("group", { name: "记忆状态筛选" });
  await filterGroup.getByRole("button", { name: "有效", exact: true }).click();
  await expect(page.locator(".memory-row")).toHaveCount(1);
  await expect(page.locator(".memory-row h2")).toContainText("校准");

  await filterGroup.getByRole("button", { name: "归档", exact: true }).click();
  await expect(page.locator(".memory-row")).toHaveCount(1);
  await expect(page.locator(".memory-row h2")).toContainText("引述");

  await filterGroup.getByRole("button", { name: "全部", exact: true }).click();
  await expect(page.locator(".memory-row")).toHaveCount(2);

  // 4. 详情（ACTIVE 长中文 + 超长连续字符）
  await page.locator(".memory-row").filter({ hasText: "校准" }).click();
  const detailBodyText = await page.locator(".detail-body").textContent();
  const [detailTitle, ...detailRemainder] = ACTIVE_DETAIL_BODY.split(/\r?\n/);
  await expect(page.getByRole("heading", { level: 2, name: detailTitle })).toBeVisible();
  expect(detailBodyText).toBe(detailRemainder.join("\n").trim());
  await expectNoClamping(page.locator(".detail-body"));
  const definitionLabels = await page.locator(".definition-strip dt").allTextContents();
  expect(definitionLabels).toEqual(["规范状态", "类型", "视角", "证据", "当前版本"]);
  await expect(page.getByRole("heading", { level: 3, name: "治理边界" })).toBeVisible();
  const actionGroup = page.getByRole("group", { name: "记忆治理动作" });
  await expect(actionGroup.getByRole("button")).toHaveCount(4);
  await expectNoHorizontalOverflow(page);
  axeScans.push(await runAxe(page, project, "detail", 2));
  screenshots.push(await captureScreenshot(page, project, "detail", 2));

  // 未接线治理动作只给出可访问反馈，绝不发出写请求。
  for (const action of ["与 hide 一起修正", "归档", "隔离"]) {
    const requestsBefore = audit.totalRequests;
    await actionGroup.getByRole("button", { name: action, exact: true }).click();
    await expect(actionGroup.getByRole("status")).toContainText(`${action}当前本地 V1 尚未接线`);
    expect(audit.totalRequests).toBe(requestsBefore);
    expect(audit.writeRequests).toBe(0);
  }

  // 永久删除已由 Task33B2 接线：点击后打开删除影响预览抽屉（R1-04 陈旧断言机械同步）。
  await page.route("**/v1/deletion-previews", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "Cache-Control": "no-store" },
      body: JSON.stringify({
        requestId: "req-delete-preview",
        resultCategory: "SUCCEEDED",
        previewId: "preview-0000-0000-0000-000000000001",
        previewRevision: 1,
        manifestHash: "00".repeat(32),
        closureMembers: [
          { ordinal: 1, memberKind: "MEMORY", targetId: ACTIVE_MEMORY_ID, disposition: "DELETE_REQUESTED" },
        ],
        evidence: [],
        sharedMemories: [],
      }),
    });
  });
  await actionGroup.getByRole("button", { name: "永久删除", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "永久删除影响预览" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "永久删除影响预览" })).toHaveCount(0);

  if (IS_MOBILE(project)) {
    await page.locator(".detail-actions").evaluate((element) => {
      window.scrollTo({ top: Math.max(0, (element as HTMLElement).offsetTop - 112), behavior: "auto" });
    });
    screenshots.push(await captureScreenshot(page, project, "detail-actions", 5));
  }

  // 长正文会把定义条与治理区推到首屏以下；保留滚动后的真实视觉证据。
  await page.locator(".governance-panel").evaluate((element) => {
    const detail = element.closest(".memory-detail") as HTMLElement | null;
    const top = (element as HTMLElement).offsetTop;
    if (window.innerWidth <= 820) window.scrollTo({ top: Math.max(0, top - 24), behavior: "auto" });
    else if (detail) detail.scrollTop = Math.max(0, top - 36);
  });
  screenshots.push(await captureScreenshot(page, project, "detail-lower", 4));
  await expectNoHorizontalOverflow(page);

  // 5. 完整证据抽屉（hide → 小林 → hide）
  const evidenceBefore = audit.evidenceRequests;
  expect(evidenceBefore).toBe(0);
  await page.getByRole("button", { name: /查看完整证据/ }).click();
  await expect(page.locator(".evidence-drawer")).toBeVisible();
  await expect(page.locator(".evidence-message")).toHaveCount(3);
  expect(audit.evidenceRequests).toBeGreaterThan(evidenceBefore);

  // hide/小林/hide 顺序
  const labels = await page.locator(".evidence-message strong").allTextContents();
  expect(labels).toEqual(["hide", "小林", "hide"]);

  // 气泡左右半对齐
  await expectBubbleAlignment(page);

  // 完整证据正文逐字存在 + 未被 line-clamp / ellipsis / 固定高度裁断
  const evidenceTexts = await page.locator(".evidence-message p").allTextContents();
  expect(evidenceTexts).toEqual(ACTIVE_EVIDENCE_ITEMS.map((it) => it.bodyText));
  for (let i = 0; i < ACTIVE_EVIDENCE_ITEMS.length; i++) {
    await expectNoClamping(page.locator(".evidence-message p").nth(i));
  }
  await expectNoHorizontalOverflow(page);

  axeScans.push(await runAxe(page, project, "drawer", 3));
  screenshots.push(await captureScreenshot(page, project, "drawer", 3));

  // 6. 关闭抽屉 → 证据正文从 DOM 消失
  await page.locator(".drawer-close").click();
  await expect(page.locator(".evidence-drawer")).toHaveCount(0);
  await expect(page.locator(".evidence-message")).toHaveCount(0);

  // 7. 重新打开 → 产生新的 evidence 请求（Query cache 已清理的公共行为证据）
  const beforeReopen = audit.evidenceRequests;
  await page.getByRole("button", { name: /查看完整证据/ }).click();
  await expect(page.locator(".evidence-message")).toHaveCount(3);
  expect(audit.evidenceRequests).toBe(beforeReopen + 1);
  await page.locator(".drawer-close").click();
  await expect(page.locator(".evidence-drawer")).toHaveCount(0);

  // 8. 返回
  if (IS_MOBILE(project)) {
    await page.locator(".mobile-back").click();
    await expect(page.locator(".archive-index")).toBeVisible();
  } else {
    await page.getByRole("link", { name: "nest 记忆档案" }).click();
    await expect(page.locator(".welcome-detail")).toBeVisible();
  }

  // 9. 单消息形态（ARCHIVED 记忆，仅 1 条证据）：当前 app 将单消息渲染为一个证据气泡。
  await page.locator(".memory-row").filter({ hasText: "引述" }).click();
  const archivedBody = "这是归档记忆的正文。它只有一条证据，用来验证单消息进入原文展示形态。";
  await expect(page.locator(".detail-copy h2")).toHaveText(archivedBody);
  await expect(page.locator(".detail-copy .detail-body")).toHaveCount(0);
  await expect(page.locator(".detail-copy").getByText(archivedBody, { exact: true })).toHaveCount(1);
  await page.getByRole("button", { name: /查看完整证据/ }).click();
  await expect(page.locator(".evidence-drawer")).toBeVisible();
  await expect(page.locator(".evidence-message")).toHaveCount(1);
  const singleText = await page.locator(".evidence-message p").textContent();
  expect(singleText?.trim()).toBe(SINGLE_EVIDENCE_BODY);
  await page.locator(".drawer-close").click();
  await expect(page.locator(".evidence-drawer")).toHaveCount(0);

  // 10. 持久存储审计：不得出现正文/token
  const storage = await runStorageAudit(page);
  expect(Object.keys(storage.localStorage)).toHaveLength(0);
  expect(Object.keys(storage.sessionStorage)).toHaveLength(0);
  expect(storage.indexedDbNames).toHaveLength(0);
  expect(storage.cacheNames).toHaveLength(0);
  expect(storage.serviceWorkers).toBe(0);

  // 11. console.error / pageerror / 未处理 rejection / Authorization / 外部请求 均为 0
  expect(audit.consoleErrors).toBe(0);
  expect(audit.expectedHttpDiagnostics).toBe(0);
  expect(audit.pageErrors).toBe(0);
  expect(audit.authorizationHeaders).toBe(0);
  expect(audit.externalRequests).toBe(0);

  recordMachineResult(testInfo, {
    kind: "core-flow",
    project,
    browser,
    coreFlow: true,
    axeScans,
    requestAudit: {
      authorizationHeaders: audit.authorizationHeaders,
      externalRequests: audit.externalRequests,
      evidenceRequests: audit.evidenceRequests,
      writeRequests: audit.writeRequests,
      totalRequests: audit.totalRequests,
      externalUrls: audit.externalUrls,
    },
    storageAudit: {
      localStorageKeys: Object.keys(storage.localStorage).length,
      sessionStorageKeys: Object.keys(storage.sessionStorage).length,
      indexedDbNames: storage.indexedDbNames.length,
      cacheNames: storage.cacheNames.length,
      serviceWorkers: storage.serviceWorkers,
    },
    consoleAudit: {
      consoleError: audit.consoleErrors,
      pageError: audit.pageErrors,
      expectedHttpDiagnostics: audit.expectedHttpDiagnostics,
    },
    screenshots,
  });
});

// ---------------------------------------------------------------------------
// 可切换 fixture 模式：empty / 401 / 403 / 404 / 422 / 500 / 503
// ---------------------------------------------------------------------------
const FIXTURE_MODES: Array<{ mode: ApiMode; expectedTitle: string }> = [
  { mode: "empty", expectedTitle: "这里还没有档案" },
  { mode: "denied-401", expectedTitle: "当前没有查看权限" },
  { mode: "denied-403", expectedTitle: "当前没有查看权限" },
  { mode: "not-found", expectedTitle: "没有找到可读取的档案" },
  { mode: "invalid", expectedTitle: "这组读取条件无效" },
  { mode: "server-500", expectedTitle: "读取完整性检查没有通过" },
  { mode: "server-503", expectedTitle: "读取完整性检查没有通过" },
];

for (const { mode, expectedTitle } of FIXTURE_MODES) {
  test(`fixture mode ${mode} renders safe panel`, async ({ page }, testInfo) => {
    const audit = await installSyntheticApi(page, mode);
    await page.goto("/");
    await expect(page.locator(".state-panel")).toContainText(expectedTitle);

    expect(audit.consoleErrors).toBe(0);
    expect(audit.pageErrors).toBe(0);
    expect(audit.expectedHttpDiagnostics).toBe(expectedHttpDiagnosticCount(mode));
    expect(audit.authorizationHeaders).toBe(0);
    expect(audit.externalRequests).toBe(0);

    recordMachineResult(testInfo, {
      kind: "fixture-mode",
      project: testInfo.project.name,
      browser: await getBrowserInfo(page),
      mode,
      requestAudit: {
        authorizationHeaders: audit.authorizationHeaders,
        externalRequests: audit.externalRequests,
        evidenceRequests: audit.evidenceRequests,
        writeRequests: audit.writeRequests,
        totalRequests: audit.totalRequests,
        externalUrls: audit.externalUrls,
      },
      consoleAudit: {
        consoleError: audit.consoleErrors,
        pageError: audit.pageErrors,
        expectedHttpDiagnostics: audit.expectedHttpDiagnostics,
      },
    });
  });
}
