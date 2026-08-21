import { expect, test, type Page } from "@playwright/test";
import {
  captureScreenshot,
  getBrowserInfo,
  recordMachineResult,
  runAxe,
  runStorageAudit,
  type AxeScan,
} from "./fixtures";

// ---------------------------------------------------------------------------
// 记忆列表稳定分页与类型筛选 —— 真实浏览器纵切（1440 / 1024 / 390）
//
// 覆盖 Task45D 的纸张类型菜单与固定分页底栏，并验证响应式、键盘/aria 与视觉门。
// ---------------------------------------------------------------------------

const PAGE_SIZE = 30;

function pgItem(i: number, memoryType: string) {
  return {
    memoryId: `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`,
    currentRevisionId: `11111111-1111-4111-8111-${String(i).padStart(12, "0")}`,
    revisionNo: 1,
    state: "ACTIVE",
    isolated: false,
    memoryType,
    perspective: "hide:actor-hide-0001",
    title: `分页记忆 ${i}`,
    summary: `第 ${i} 条记忆的摘要`,
    sourceAvailability: "AVAILABLE",
    uncertaintyCode: "CONFIRMED",
    updatedAt: "2026-08-10T00:00:00.000Z",
  };
}

const TYPES = ["EVENT", "CLAIM", "QUOTE", "INTERPRETATION", "CALIBRATION", "PRINCIPLE"];
const ALL_ITEMS = Array.from({ length: 35 }, (_, i) => pgItem(i + 1, TYPES[i % TYPES.length]));

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
  expect(overflow).toBe(false);
}

async function expectPaginationFooterInViewport(page: Page) {
  const layout = await page.locator(".pagination-footer").evaluate((footer) => {
    const footerRect = footer.getBoundingClientRect();
    const listRect = document.querySelector(".memory-list")!.getBoundingClientRect();
    return {
      footerTop: footerRect.top,
      footerBottom: footerRect.bottom,
      listBottom: listRect.bottom,
      viewportHeight: window.innerHeight,
    };
  });
  expect(layout.footerTop).toBeGreaterThanOrEqual(0);
  expect(layout.footerBottom).toBeLessThanOrEqual(layout.viewportHeight + 1);
  expect(layout.listBottom).toBeLessThanOrEqual(layout.footerTop + 1);
}

function installPagedApi(page: Page, log: { typeParams: Array<string | null>; cursors: Array<string | null> }) {
  return page.route((url) => url.pathname === "/v1/memories", async (route) => {
    const url = new URL(route.request().url());
    const cursor = url.searchParams.get("cursor");
    const memoryType = url.searchParams.get("memoryType");
    log.typeParams.push(memoryType);
    log.cursors.push(cursor);
    let items = ALL_ITEMS;
    if (memoryType) items = items.filter((it) => it.memoryType === memoryType);
    let pageItems: typeof items;
    let nextCursor: string | undefined;
    if (!cursor) {
      pageItems = items.slice(0, PAGE_SIZE);
      nextCursor = items.length > PAGE_SIZE ? "cursor-1" : undefined;
    } else {
      pageItems = items.slice(PAGE_SIZE);
      nextCursor = undefined;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { "Cache-Control": "no-store" },
      body: JSON.stringify({
        requestId: "req-synthetic-paging",
        resultCategory: "SUCCEEDED",
        items: pageItems,
        ...(nextCursor ? { nextCursor } : {}),
      }),
    });
  });
}

test("type filter + load more vertical flow", async ({ page }, testInfo) => {
  const project = testInfo.project.name;
  const log = { typeParams: [] as Array<string | null>, cursors: [] as Array<string | null> };
  await installPagedApi(page, log);
  const browser = await getBrowserInfo(page);
  const screenshots: string[] = [];
  const axeScans: AxeScan[] = [];

  await page.goto("/");

  // 1. 首屏 30 条 + 可见的独立底栏 + “已加载 30 条”
  await expect(page.locator(".memory-row")).toHaveCount(30);
  await expect(page.getByRole("button", { name: "加载更多" })).toBeVisible();
  await expect(page.getByText("已加载 30 条")).toBeVisible();
  await expectPaginationFooterInViewport(page);
  await expect(page.locator(".memory-list .load-more-button")).toHaveCount(0);
  expect(log.cursors).toEqual([null]);
  await expectNoHorizontalOverflow(page);

  // 2. 纸张菜单：无原生 select，默认不发送 memoryType。
  const typeTrigger = page.getByRole("button", { name: "按记忆类型筛选，当前全部" });
  await expect(typeTrigger).toBeVisible();
  await expect(page.locator("select")).toHaveCount(0);
  await expect(typeTrigger).toHaveAttribute("aria-expanded", "false");
  expect(log.typeParams[0]).toBeNull();
  screenshots.push(await captureScreenshot(page, project, "type-filter-closed", 1));

  // 3. 键盘可访问：触发器 ArrowDown 打开，当前项获得焦点，细玫瑰 focus-visible 存在。
  await typeTrigger.focus();
  const focusOutline = await typeTrigger.evaluate((el) => getComputedStyle(el).outlineStyle);
  expect(focusOutline).toBe("solid");
  await page.keyboard.press("ArrowDown");
  const allOption = page.getByRole("radio", { name: "全部类型" });
  await expect(allOption).toBeFocused();
  await expect(allOption).toHaveAttribute("aria-checked", "true");
  const popover = page.locator(".type-filter-popover");
  await expect(popover).toBeVisible();
  const popoverBox = await popover.boundingBox();
  expect(popoverBox?.width).toBeLessThanOrEqual(156);
  screenshots.push(await captureScreenshot(page, project, "type-filter-open", 2));

  // 4. 选择后关闭，发送精确 wire enum；全部类型省略 memoryType。
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("radio", { name: "事件" })).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(page.getByRole("radio", { name: "判断" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(popover).toHaveCount(0);
  await expect(page.getByText(/已加载|共/)).toBeVisible();
  await expect(page.locator(".memory-row")).toHaveCount(6); // 35 项中 CLAIM 有 6 项
  expect(log.typeParams[log.typeParams.length - 1]).toBe("CLAIM");
  await expect(page.getByRole("button", { name: "加载更多" })).not.toBeVisible();
  axeScans.push(await runAxe(page, project, "type-filter", 1));

  await page.getByRole("button", { name: "按记忆类型筛选，当前判断" }).click();
  await page.getByRole("radio", { name: "全部类型" }).click();
  await expect(page.locator(".memory-row")).toHaveCount(30);
  expect(log.typeParams[log.typeParams.length - 1]).toBeNull();

  // 5. 加载更多：追加 5 条到 35，末页按钮消失且显示“共 35 条”
  await page.getByRole("button", { name: "加载更多" }).click();
  await expect(page.locator(".memory-row")).toHaveCount(35);
  await expect(page.getByRole("button", { name: "加载更多" })).not.toBeVisible();
  await expect(page.getByText("共 35 条")).toBeVisible();
  // Type-filter changes each legitimately start a fresh page 1 (cursor=null); only the load-more
  // request carries a non-null cursor.
  expect(log.cursors.filter((c) => c !== null)).toEqual(["cursor-1"]);
  await expectNoHorizontalOverflow(page);
  axeScans.push(await runAxe(page, project, "loaded", 2));
  screenshots.push(await captureScreenshot(page, project, "loaded", 2));

  // 6. 存储与泄漏审计
  const storage = await runStorageAudit(page);
  expect(Object.keys(storage.localStorage)).toHaveLength(0);
  expect(Object.keys(storage.sessionStorage)).toHaveLength(0);

  recordMachineResult(testInfo, {
    kind: "paging-typefilter",
    project,
    browser,
    cursors: log.cursors,
    typeParams: log.typeParams,
    axeScans,
    screenshots,
  });
});
