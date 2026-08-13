import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { QueryClient } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../App";
import { EVIDENCE_KEY, queryClientFactory } from "../query";

const memoryId = "11111111-1111-4111-8111-111111111111";
const revisionId = "22222222-2222-4222-8222-222222222222";
const bodyText = "暖灰的规范正文\n第二行仍属于当前版本。";
const evidenceBodies = ["hide 的必要证据", "小林的必要证据", "未知参与者的必要证据"];

const listResponse = {
  requestId: "018f3f00-0000-7000-8000-000000000001",
  resultCategory: "SUCCEEDED",
  items: [{
    memoryId,
    currentRevisionId: revisionId,
    revisionNo: 3,
    state: "ACTIVE",
    isolated: false,
    memoryType: "INTERPRETATION",
    perspective: "xiaolin:33333333-3333-4333-8333-333333333333",
    title: "暖灰的规范正文",
    summary: "只来自当前授权 revision 的摘要",
    sourceAvailability: "AVAILABLE",
    uncertaintyCode: "CONFIRMED",
    updatedAt: "2026-08-12T09:30:00Z",
  }],
};

const detailResponse = {
  requestId: "018f3f00-0000-7000-8000-000000000002",
  resultCategory: "SUCCEEDED",
  memory: {
    memoryId,
    currentRevisionId: revisionId,
    revisionNo: 3,
    state: "ACTIVE",
    memoryType: "INTERPRETATION",
    perspectiveActorId: "33333333-3333-4333-8333-333333333333",
    bodyText,
    updatedAt: "2026-08-12T09:30:00Z",
    evidenceCount: 3,
    uncertaintyCode: "CONFIRMED",
  },
};

const multiEvidence = {
  requestId: "018f3f00-0000-7000-8000-000000000003",
  resultCategory: "SUCCEEDED",
  memoryId,
  currentRevisionId: revisionId,
  revisionNo: 3,
  evidenceItems: [
    evidenceItem(1, "hide:44444444-4444-4444-8444-444444444444", evidenceBodies[0]),
    evidenceItem(2, "xiaolin:33333333-3333-4333-8333-333333333333", evidenceBodies[1]),
    evidenceItem(3, "actor:55555555-5555-4555-8555-555555555555", evidenceBodies[2]),
  ],
};

function evidenceItem(ordinal: number, actorStableRef: string, text: string) {
  return {
    anchorId: `aaaaaaaa-aaaa-4aaa-8aaa-${String(ordinal).padStart(12, "0")}`,
    sourceUnitId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(ordinal).padStart(12, "0")}`,
    ordinal,
    actorId: `cccccccc-cccc-4ccc-8ccc-${String(ordinal).padStart(12, "0")}`,
    actorKind: "SYNTHETIC",
    actorStableRef,
    occurredAt: `2026-08-12T09:30:0${ordinal}Z`,
    bodyText: text,
  };
}

let evidenceRequests = 0;
let writeRequests = 0;
let authorizationHeaders: Array<string | null> = [];

const server = setupServer(
  http.get("*/v1/memories", ({ request }) => {
    authorizationHeaders.push(request.headers.get("authorization"));
    return HttpResponse.json(listResponse);
  }),
  http.get("*/v1/memories/:memoryId", () => HttpResponse.json(detailResponse)),
  http.get("*/v1/memories/:memoryId/evidence", () => {
    evidenceRequests += 1;
    return HttpResponse.json(multiEvidence);
  }),
  http.post("*/v1/*", () => {
    writeRequests += 1;
    return new HttpResponse(null, { status: 500 });
  }),
  http.put("*/v1/*", () => {
    writeRequests += 1;
    return new HttpResponse(null, { status: 500 });
  }),
  http.patch("*/v1/*", () => {
    writeRequests += 1;
    return new HttpResponse(null, { status: 500 });
  }),
  http.delete("*/v1/*", () => {
    writeRequests += 1;
    return new HttpResponse(null, { status: 500 });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
beforeEach(() => {
  evidenceRequests = 0;
  writeRequests = 0;
  authorizationHeaders = [];
  window.history.replaceState({}, "", "/memories");
});

async function renderDetail(client: QueryClient = queryClientFactory()) {
  const user = userEvent.setup();
  render(<App client={client} />);
  await user.click(await screen.findByRole("button", { name: /暖灰的规范正文/ }));
  await screen.findByText((content, element) =>
    element?.classList.contains("detail-body") === true && content.includes("第二行仍属于当前版本。"));
  return { user, client };
}

describe("记忆档案生产纵切", () => {
  it("详情恢复已验收结构、精确五项定义与四个未接线治理入口", async () => {
    const { user } = await renderDetail();
    const detailCopy = document.querySelector(".detail-copy") as HTMLElement;
    expect(within(detailCopy).getByRole("heading", { level: 2, name: "暖灰的规范正文" })).toBeVisible();
    expect(screen.getByText("第二行仍属于当前版本。")).toHaveClass("detail-body");

    const definitions = document.querySelectorAll(".definition-strip dt");
    expect(Array.from(definitions, (node) => node.textContent)).toEqual([
      "规范状态", "类型", "视角", "证据", "当前版本",
    ]);
    expect(screen.getByRole("heading", { level: 3, name: "治理边界" })).toBeVisible();

    const actions = screen.getByRole("group", { name: "记忆治理动作" });
    const actionNames = ["与 hide 一起修正", "归档", "隔离", "永久删除"];
    expect(within(actions).getAllByRole("button")).toHaveLength(4);
    for (const name of actionNames) {
      const button = within(actions).getByRole("button", { name: new RegExp(`^${name}$`) });
      await user.click(button);
      expect(within(actions).getByRole("status")).toHaveTextContent(`${name}当前本地 V1 尚未接线；未发出写请求。`);
    }
    expect(writeRequests).toBe(0);
  });

  it("bodyText 第一条非空行作为标题，剩余正文单独渲染", async () => {
    const { user } = await renderDetail();
    const detailCopy = document.querySelector(".detail-copy") as HTMLElement;
    expect(within(detailCopy).getByRole("heading", { level: 2, name: "暖灰的规范正文" })).toBeVisible();
    expect(document.querySelector(".detail-body")?.textContent).toBe("第二行仍属于当前版本。");

    cleanup();
    window.history.replaceState({}, "", "/memories");
    server.use(http.get("*/v1/memories/:memoryId", () => HttpResponse.json({
      ...detailResponse,
      memory: { ...detailResponse.memory, bodyText: "只有这一行，不得重复。" },
    })));
    render(<App client={queryClientFactory()} />);
    await user.click(await screen.findByRole("button", { name: /暖灰的规范正文/ }));
    expect(await within(document.querySelector(".detail-copy") as HTMLElement).findByRole("heading", { level: 2, name: "只有这一行，不得重复。" })).toBeVisible();
    expect(document.querySelector(".detail-body")).toBeNull();
    expect(screen.getAllByText("只有这一行，不得重复。")).toHaveLength(1);
  });

  it("列表到详情不预取证据，打开后读取，关闭后清除 cache 并归还焦点", async () => {
    const { user, client } = await renderDetail();
    expect(evidenceRequests).toBe(0);
    expect(authorizationHeaders).toEqual([null]);

    const trigger = screen.getByRole("button", { name: /查看完整证据/ });
    await user.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "这条记忆保存的完整证据" });
    expect(evidenceRequests).toBe(1);
    expect(within(dialog).getByText(evidenceBodies[0])).toBeVisible();
    expect(client.getQueriesData({ queryKey: [EVIDENCE_KEY, memoryId] }).length).toBe(1);

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
    expect(client.getQueriesData({ queryKey: [EVIDENCE_KEY, memoryId] })).toHaveLength(0);
  });

  it("多消息按 hide 左、小林右、未知 actor 中立呈现", async () => {
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    await screen.findByText(evidenceBodies[2]);

    expect(screen.getByText(evidenceBodies[0]).closest("article")).toHaveClass("hide");
    expect(screen.getByText(evidenceBodies[1]).closest("article")).toHaveClass("xiaolin");
    expect(screen.getByText(evidenceBodies[2]).closest("article")).toHaveClass("neutral");
    expect(screen.getByText("未标注参与者")).toBeVisible();
  });

  it("单消息使用原文样式，不伪造成聊天记录", async () => {
    server.use(http.get("*/v1/memories/:memoryId/evidence", () => HttpResponse.json({
      ...multiEvidence,
      evidenceItems: [evidenceItem(1, "xiaolin:33333333-3333-4333-8333-333333333333", "单条完整原文")],
    })));
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    expect((await screen.findByText("单条完整原文")).tagName).toBe("BLOCKQUOTE");
    expect(screen.queryByLabelText("多人证据记录")).not.toBeInTheDocument();
  });

  it.each([
    [401, "当前没有查看权限"],
    [403, "当前没有查看权限"],
    [422, "这组读取条件无效"],
    [503, "读取完整性检查没有通过"],
  ])("列表 HTTP %i 显示明确失败分支", async (status, message) => {
    server.use(http.get("*/v1/memories", () => new HttpResponse(null, { status })));
    render(<App client={queryClientFactory()} />);
    expect(await screen.findByText(message)).toBeVisible();
  });

  it("详情 404 使用安全 not-found，denied 不渲染对象标题或正文", async () => {
    server.use(http.get("*/v1/memories/:memoryId", () => new HttpResponse(null, { status: 404 })));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await user.click(await screen.findByRole("button", { name: /暖灰的规范正文/ }));
    expect(await screen.findByText("没有找到可读取的档案")).toBeVisible();
    expect(screen.queryByText(bodyText)).not.toBeInTheDocument();

    cleanup();
    window.history.replaceState({}, "", "/memories");
    server.use(http.get("*/v1/memories", () => new HttpResponse(null, { status: 403 })));
    render(<App client={queryClientFactory()} />);
    await screen.findByText("当前没有查看权限");
    expect(screen.queryByText("暖灰的规范正文")).not.toBeInTheDocument();
    expect(screen.queryByText(bodyText)).not.toBeInTheDocument();
  });

  it("搜索和状态同步到 URL，但正文与证据不进入 URL 或持久存储", async () => {
    window.history.replaceState({}, "", "/memories?state=ARCHIVED&query=%E6%9A%96%E7%81%B0");
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    expect(await screen.findByDisplayValue("暖灰")).toBeVisible();
    expect(screen.getByRole("button", { name: "归档" })).toHaveAttribute("aria-pressed", "true");
    await user.clear(screen.getByRole("searchbox"));
    await user.type(screen.getByRole("searchbox"), "短查询");
    await waitFor(() => expect(window.location.search).toContain("query=%E7%9F%AD%E6%9F%A5%E8%AF%A2"));
    expect(window.location.href).not.toContain(encodeURIComponent(bodyText));
    evidenceBodies.forEach((body) => expect(window.location.href).not.toContain(encodeURIComponent(body)));
    expect(setItem).not.toHaveBeenCalled();
    expect("indexedDB" in window).toBe(false);
    setItem.mockRestore();
  });

  it("首页/档案链接有 accessible name，卡片标题为 level 2，筛选按钮语义唯一", async () => {
    render(<App client={queryClientFactory()} />);

    // R1-04：首页链接（wordmark）与档案链接（导航）都有稳定中文 accessible name
    expect(screen.getByRole("link", { name: "nest 记忆档案" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "记忆档案" })).toBeInTheDocument();

    // R1-05：卡片标题为语义 level 2
    expect(await screen.findByRole("heading", { level: 2, name: "暖灰的规范正文" })).toBeInTheDocument();

    // R1-01：三个筛选按钮在语义容器内可唯一定位
    const filterGroup = screen.getByRole("group", { name: "记忆状态筛选" });
    expect(within(filterGroup).getAllByRole("button")).toHaveLength(3);
    expect(within(filterGroup).getByRole("button", { name: "全部" })).toBeInTheDocument();
    expect(within(filterGroup).getByRole("button", { name: "有效" })).toBeInTheDocument();
    expect(within(filterGroup).getByRole("button", { name: "归档" })).toBeInTheDocument();
  });
});
