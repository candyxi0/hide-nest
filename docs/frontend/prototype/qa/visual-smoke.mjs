import { createRequire } from "node:module";
import fs from "node:fs/promises";
import path from "node:path";

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/tangx/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright");

const root = path.resolve(import.meta.dirname, "..");
const screenshots = path.join(root, "screenshots");
await fs.mkdir(screenshots, { recursive: true });

const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files/Google/Chrome/Application/chrome.exe",
});

const results = [];
const record = (name, passed, detail = "") => results.push({ name, passed, detail });

async function verifyNoHorizontalOverflow(page, label) {
  const sizes = await page.evaluate(() => ({
    body: document.body.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  record(`${label}: no horizontal overflow`, sizes.body <= sizes.viewport + 1, JSON.stringify(sizes));
}

async function main() {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    reducedMotion: "reduce",
  });
  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  page.on("console", message => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("pageerror", error => pageErrors.push(error.message));

  await page.goto("http://127.0.0.1:4173/#memories", { waitUntil: "networkidle" });
  await page.screenshot({ path: path.join(screenshots, "desktop-memory-archive.png"), fullPage: true });
  record("desktop title visible", await page.getByRole("heading", { name: "留下来的，不必喧哗" }).isVisible());
  record("six synthetic memories listed", (await page.locator(".memory-row").count()) === 6);
  record("desktop mobile menu hidden", await page.locator("#mobileMenuButton").isHidden());
  record("page title uses consistent slant", (await page.locator("#pageTitle").evaluate(el => getComputedStyle(el).transform)) !== "none");
  record("evidence is labeled as a minimal excerpt", await page.getByText("最小必要片段，不是整段原文").isVisible());
  const [correctBox, archiveBox, isolateBox, deleteBox] = await Promise.all([
    page.locator("[data-action='correct']").boundingBox(),
    page.locator("[data-action='archive']").boundingBox(),
    page.locator("[data-action='isolate']").boundingBox(),
    page.locator("[data-action='delete']").boundingBox(),
  ]);
  record("governance actions have clear hierarchy", Boolean(
    correctBox && archiveBox && isolateBox && deleteBox &&
    correctBox.width > archiveBox.width * 1.8 &&
    Math.abs(archiveBox.y - isolateBox.y) < 2 &&
    deleteBox.y > archiveBox.y + archiveBox.height
  ));
  const memoryText = await page.locator("#memoriesView").innerText();
  record("selected protocol labels are Chinese", !/MINIMUM SUFFICIENT|RELATIONS \/ CURRENT VIEW|AccessPolicy|SUPERSEDES|PART_OF_TRAJECTORY/.test(memoryText));
  await verifyNoHorizontalOverflow(page, "desktop archive");

  record("complete evidence entry is visible", await page.locator("[data-view-evidence]").isVisible());
  record("evidence preview is visibly shortened", (await page.locator(".evidence-entry blockquote").first().innerText()).endsWith("……」"));
  await page.locator("[data-view-evidence]").click();
  await page.screenshot({ path: path.join(screenshots, "desktop-complete-evidence.png") });
  record("complete evidence reader opens", await page.getByRole("heading", { name: "这条记忆保存的完整证据" }).isVisible());
  record("all saved evidence entries render", (await page.locator(".full-evidence-entry").count()) === 2);
  record("multi-message evidence uses conversation layout", (await page.locator(".evidence-conversation").count()) === 2);
  record("all messages inside saved boundaries render", (await page.locator(".evidence-message").count()) === 5);
  record("complete evidence body is not truncated", (await page.locator(".evidence-message p").last().innerText()).includes("现在觉得关系缓和了不少"));
  const hideMessageBox = await page.locator(".evidence-conversation").first().locator(".is-hide").first().boundingBox();
  const xiaolinMessageBox = await page.locator(".evidence-conversation").first().locator(".is-xiaolin").first().boundingBox();
  record("hide stays left and Xiaolin stays right", Boolean(hideMessageBox && xiaolinMessageBox && xiaolinMessageBox.x > hideMessageBox.x + 40));
  record("reader distinguishes evidence from full conversation", await page.getByText("它不等于整场原对话。", { exact: false }).first().isVisible());
  record("evidence reader focuses close", await page.locator("#drawerClose").evaluate(el => el === document.activeElement));
  await page.keyboard.press("Escape");
  record("escape closes evidence reader", await page.locator("#actionDrawer").isHidden());

  await page.locator("body").press("/");
  record("slash focuses search", await page.locator("#memorySearch").evaluate(el => el === document.activeElement));
  await page.locator("#memorySearch").fill("暖灰");
  await page.waitForTimeout(350);
  record("search filters to one", (await page.locator(".memory-row").count()) === 1);
  await page.locator("#memorySearch").fill("");
  await page.waitForTimeout(350);

  await page.locator("[data-action='isolate']").click();
  record("isolation preview opens", await page.getByRole("heading", { name: "隔离这条记忆" }).isVisible());
  record("drawer starts on cancel", await page.locator("[data-drawer-cancel]").evaluate(el => el === document.activeElement));
  await page.keyboard.press("Escape");
  record("escape closes drawer", await page.locator("#actionDrawer").isHidden());

  await page.locator("[data-route='reviews']").click();
  record("review route renders", await page.getByRole("heading", { name: "待续，不等于已经确认" }).isVisible());
  record("review page title keeps slant", (await page.locator("#pageTitle").evaluate(el => getComputedStyle(el).transform)) !== "none");
  await page.locator("[data-route='runs']").click();
  record("run timeline renders", (await page.locator(".run-step").count()) === 4);
  record("run page title keeps slant", (await page.locator("#pageTitle").evaluate(el => getComputedStyle(el).transform)) !== "none");
  record("run phases use Chinese labels", await page.getByText("检索已就绪", { exact: true }).last().isVisible());
  await page.locator("[data-route='status']").click();
  record("system status renders six cells", (await page.locator(".status-cell").count()) === 6);
  record("status page title keeps slant", (await page.locator("#pageTitle").evaluate(el => getComputedStyle(el).transform)) !== "none");
  record("status labels use Chinese", await page.getByText("正在追赶", { exact: true }).isVisible());

  await page.locator("#scenarioSelect").selectOption("failed");
  record("failure keeps honest global notice", await page.locator("#globalNotice").isVisible());
  await page.screenshot({ path: path.join(screenshots, "desktop-connection-failure.png"), fullPage: true });
  await verifyNoHorizontalOverflow(page, "desktop failure");

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("http://127.0.0.1:4173/?viewport=mobile#memories", { waitUntil: "networkidle" });
  await page.screenshot({ path: path.join(screenshots, "mobile-memory-list.png"), fullPage: true });
  record("mobile menu visible", await page.locator("#mobileMenuButton").isVisible());
  await verifyNoHorizontalOverflow(page, "mobile list");
  await page.locator(".memory-row").first().click();
  record("mobile opens detail", await page.locator("#memoriesView").evaluate(el => el.classList.contains("detail-open")));
  record("mobile back is visible", await page.locator("[data-mobile-back]").isVisible());
  await page.screenshot({ path: path.join(screenshots, "mobile-memory-detail.png"), fullPage: true });
  await verifyNoHorizontalOverflow(page, "mobile detail");
  await page.locator("[data-view-evidence]").click();
  await page.waitForTimeout(120);
  record("mobile complete evidence reader opens", await page.getByRole("heading", { name: "这条记忆保存的完整证据" }).isVisible());
  await page.locator("#actionDrawer").evaluate(element => { element.scrollTop = 600; });
  await page.screenshot({ path: path.join(screenshots, "mobile-complete-evidence.png") });
  await verifyNoHorizontalOverflow(page, "mobile complete evidence");
  await page.locator("#drawerClose").click();
  await page.locator("[data-mobile-back]").click();
  record("mobile returns to list", !(await page.locator("#memoriesView").evaluate(el => el.classList.contains("detail-open"))));

  record("console has no errors", consoleErrors.length === 0, consoleErrors.join(" | "));
  record("page has no uncaught errors", pageErrors.length === 0, pageErrors.join(" | "));

  await context.close();
  const failed = results.filter(result => !result.passed);
  const report = {
    status: failed.length ? "FAIL" : "PASS",
    checkedAt: new Date().toISOString(),
    browser: "Google Chrome headless via Playwright",
    results,
    failed: failed.map(result => result.name),
  };
  await fs.writeFile(path.join(root, "qa-result.json"), `${JSON.stringify(report, null, 2)}\n`, "utf8");
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  if (failed.length) process.exitCode = 1;
}

try {
  await main();
} finally {
  await browser.close();
}
