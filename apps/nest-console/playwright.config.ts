import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "@playwright/test";
import { QA_DIRS } from "./e2e/fixtures";

// 本配置文件位于 apps/nest-console/。生产 preview 由 run-browser-qa.ps1 启动并绑定 127.0.0.1:4173。
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPORTER_PATH = path.join(__dirname, "e2e", "fixtures.ts");

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 90_000,
  expect: { timeout: 10_000 },

  outputDir: path.join(QA_DIRS.qaDir, ".playwright-artifacts"),

  reporter: [
    ["line"],
    ["html", { outputFolder: QA_DIRS.playwrightReportDir, open: "never" }],
    [REPORTER_PATH],
  ],

  use: {
    baseURL: "http://127.0.0.1:4173",
    channel: process.env.HIDE_NEST_QA_BROWSER_CHANNEL || undefined,
    launchOptions: process.env.HIDE_NEST_QA_BROWSER_EXECUTABLE
      ? { executablePath: process.env.HIDE_NEST_QA_BROWSER_EXECUTABLE }
      : {},
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
    video: "off",
  },

  projects: [
    { name: "desktop-1440", use: { viewport: { width: 1440, height: 900 } } },
    { name: "compact-1024", use: { viewport: { width: 1024, height: 768 } } },
    { name: "mobile-390", use: { viewport: { width: 390, height: 844 } } },
  ],
});
