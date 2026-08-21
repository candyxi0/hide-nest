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

// The list query is an infinite query; its cache holds { pages, pageParams }.
type ListCache = {
  pages: Array<{ items: Array<{ memoryId: string }> }>;
  pageParams: unknown[];
};

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
    currentPolicyRevisionNo: 1,
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
    evidenceItem(1, "hide", evidenceBodies[0]),
    evidenceItem(2, "小林", evidenceBodies[1]),
    evidenceItem(3, "", evidenceBodies[2]),
  ],
};

function evidenceItem(ordinal: number, displayLabel: string, text: string, anchorId?: string, occurredAt?: string) {
  return {
    anchorId: anchorId ?? `aaaaaaaa-aaaa-4aaa-8aaa-${String(ordinal).padStart(12, "0")}`,
    sourceUnitId: `bbbbbbbb-bbbb-4bbb-8bbb-${String(ordinal).padStart(12, "0")}`,
    ordinal,
    actorId: `cccccccc-cccc-4ccc-8ccc-${String(ordinal).padStart(12, "0")}`,
    actorKind: "SYNTHETIC",
    actorStableRef: "a-" + ordinal,
    displayLabel,
    occurredAt: occurredAt ?? `2026-08-12T09:30:0${ordinal}Z`,
    bodyText: text,
  };
}

let evidenceRequests = 0;
let writeRequests = 0;
let authorizationHeaders: Array<string | null> = [];

const runId = "aaaaaaaa-0000-4000-8000-0000000000de";

function closureMember(ordinal: number, memberKind: string, disposition: string, targetRevisionRef?: number) {
  return {
    ordinal,
    memberKind,
    targetId: `${String(ordinal).padStart(8, "0")}-0000-4000-8000-${String(ordinal).padStart(12, "0")}`,
    targetRevisionRef,
    disposition,
  };
}

const previewResponse = {
  requestId: "018f3f00-0000-7000-8000-000000000004",
  resultCategory: "SUCCEEDED",
  previewId: "99999999-9999-4999-8999-999999999999",
  previewRevision: 1,
  manifestHash: "ab".repeat(32),
  closureMembers: [
    closureMember(1, "MEMORY", "DELETE_REQUESTED"),
    closureMember(2, "MEMORY_REVISION", "DELETE_REQUESTED", 3),
    closureMember(3, "SOURCE_ANCHOR", "DELETE_CANDIDATE"),
    closureMember(4, "SOURCE_UNIT", "DELETE_CANDIDATE"),
    closureMember(5, "SOURCE_PAYLOAD", "DELETE_CANDIDATE"),
  ],
  evidence: [
    {
      anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
      ordinal: 1,
      actorId: "44444444-4444-4444-8444-444444444444",
      actorStableRef: "a-1",
      displayLabel: "hide",
      occurredAt: "2026-08-12T09:30:01Z",
      bodyText: evidenceBodies[0],
      sharedByMemoryIds: [],
    },
    {
      anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
      ordinal: 2,
      actorId: "33333333-3333-4333-8333-333333333333",
      actorStableRef: "a-2",
      displayLabel: "小林",
      occurredAt: "2026-08-12T09:30:02Z",
      bodyText: evidenceBodies[1],
      sharedByMemoryIds: [],
    },
  ],
  sharedMemories: [],
};

const confirmResponse = {
  requestId: "018f3f00-0000-7000-8000-000000000005",
  resultCategory: "SUCCEEDED",
  runId,
  statusUrl: `/v1/deletion-runs/${runId}`,
  phase: "RECEIVED",
};

const runResponse = {
  requestId: "018f3f00-0000-7000-8000-000000000006",
  resultCategory: "SUCCEEDED",
  runId,
  phase: "CANONICAL_COMMITTED",
  retryable: false,
};

let deletionPreviewRequests = 0;
let deletionConfirmRequests = 0;
let deletionRunRequests = 0;
let previewIdempotencyKeys: string[] = [];
let confirmIdempotencyKeys: string[] = [];
let deletionAuthHeaders: Array<string | null> = [];
let deletionCapabilityHeaders: Array<string | null> = [];

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
  http.post("*/v1/deletion-previews", ({ request }) => {
    deletionPreviewRequests += 1;
    previewIdempotencyKeys.push(request.headers.get("idempotency-key") ?? "");
    deletionAuthHeaders.push(request.headers.get("authorization"));
    deletionCapabilityHeaders.push(request.headers.get("x-action-capability"));
    return HttpResponse.json(previewResponse);
  }),
  http.post("*/v1/deletion-previews/:id/confirm", ({ request }) => {
    deletionConfirmRequests += 1;
    confirmIdempotencyKeys.push(request.headers.get("idempotency-key") ?? "");
    deletionAuthHeaders.push(request.headers.get("authorization"));
    deletionCapabilityHeaders.push(request.headers.get("x-action-capability"));
    return HttpResponse.json(confirmResponse, { status: 202 });
  }),
  http.get("*/v1/deletion-runs/:runId", () => {
    deletionRunRequests += 1;
    return HttpResponse.json(runResponse);
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
  deletionPreviewRequests = 0;
  deletionConfirmRequests = 0;
  deletionRunRequests = 0;
  previewIdempotencyKeys = [];
  confirmIdempotencyKeys = [];
  deletionAuthHeaders = [];
  deletionCapabilityHeaders = [];
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
  it("详情恢复已验收结构、精确五项定义与三个未接线治理入口", async () => {
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
    expect(within(actions).getAllByRole("button")).toHaveLength(4);
    const unavailableNames = ["与 hide 一起修正", "归档", "隔离"];
    for (const name of unavailableNames) {
      const button = within(actions).getByRole("button", { name: new RegExp(`^${name}$`) });
      await user.click(button);
      expect(within(actions).getByRole("status")).toHaveTextContent(`${name}当前本地 V1 尚未接线；未发出写请求。`);
    }
    expect(writeRequests).toBe(0);
    expect(within(actions).getByRole("button", { name: "永久删除" })).toBeVisible();
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
    expect(screen.getByText("未知说话者")).toBeVisible();
  });

  it("单消息段仍显示一个气泡，不使用表格或 blockquote 特例", async () => {
    server.use(http.get("*/v1/memories/:memoryId/evidence", () => HttpResponse.json({
      ...multiEvidence,
      evidenceItems: [evidenceItem(1, "小林", "单条完整原文")],
    })));
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    const body = await screen.findByText("单条完整原文");
    expect(body.closest("article")).toHaveClass("xiaolin");
    expect(document.querySelector(".single-evidence")).toBeNull();
    expect(document.querySelector(".evidence-meta")).toBeNull();
    expect(document.querySelector("blockquote")).toBeNull();
  });

  it("单段证据显示对应记忆与轻量摘要，无证据编号与大表格字段", async () => {
    server.use(http.get("*/v1/memories/:memoryId/evidence", () => HttpResponse.json({
      ...multiEvidence,
      evidenceItems: [
        evidenceItem(1, "hide", "单段第一条", "anchor-shared-1"),
        evidenceItem(2, "小林", "单段第二条", "anchor-shared-1"),
      ],
    })));
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    const dialog = await screen.findByRole("dialog", { name: "这条记忆保存的完整证据" });

    expect(within(dialog).getByText((content) => content.startsWith("对应记忆："))).toHaveTextContent("暖灰的规范正文");
    expect(within(dialog).getByText(/1 段，共 2 条消息/)).toBeVisible();
    expect(within(dialog).getByText("单段第一条")).toBeVisible();
    expect(within(dialog).getByText("单段第二条")).toBeVisible();
    expect(within(dialog).queryByText(/第 \d+ 段/)).not.toBeInTheDocument();
    expect(dialog.textContent).not.toContain("来源");
    expect(dialog.textContent).not.toContain("参与者");
    expect(dialog.textContent).not.toContain("截取边界");
    expect(dialog.textContent).not.toContain("展示完整性");
    expect(dialog.textContent).not.toContain("保留理由");
    expect(dialog.textContent).not.toContain("hide 说");
    expect(dialog.textContent).not.toContain("小林说");
  });

  it("多段证据按 anchor 分组，不串段且每段带紧凑小标题", async () => {
    server.use(http.get("*/v1/memories/:memoryId/evidence", () => HttpResponse.json({
      ...multiEvidence,
      evidenceItems: [
        evidenceItem(1, "hide", "段一消息", "anchor-a"),
        evidenceItem(2, "小林", "段一第二条", "anchor-a"),
        evidenceItem(3, "hide", "段二消息", "anchor-b"),
      ],
    })));
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    const dialog = await screen.findByRole("dialog", { name: "这条记忆保存的完整证据" });

    expect(within(dialog).getByText(/2 段，共 3 条消息/)).toBeVisible();
    expect(within(dialog).getByText(/第 1 段 · 2 条消息/)).toBeVisible();
    expect(within(dialog).getByText(/第 2 段 · 1 条消息/)).toBeVisible();

    const segmentOne = within(dialog).getByLabelText("第 1 段证据");
    const segmentTwo = within(dialog).getByLabelText("第 2 段证据");
    expect(within(segmentOne).getByText("段一消息")).toBeVisible();
    expect(within(segmentOne).getByText("段一第二条")).toBeVisible();
    expect(within(segmentOne).queryByText("段二消息")).not.toBeInTheDocument();
    expect(within(segmentTwo).getByText("段二消息")).toBeVisible();
    expect(within(segmentTwo).queryByText("段一消息")).not.toBeInTheDocument();
  });

  it("证据时间范围动态派生：同分钟单时间、跨分钟紧凑范围", async () => {
    server.use(http.get("*/v1/memories/:memoryId/evidence", () => HttpResponse.json({
      ...multiEvidence,
      evidenceItems: [
        evidenceItem(1, "hide", "同一分钟", "anchor-t1", "2026-08-12T10:00:00Z"),
        evidenceItem(2, "小林", "同一分钟二", "anchor-t1", "2026-08-12T10:00:30Z"),
        evidenceItem(3, "hide", "跨分钟一", "anchor-t2", "2026-08-12T10:05:00Z"),
        evidenceItem(4, "小林", "跨分钟二", "anchor-t2", "2026-08-12T10:10:00Z"),
      ],
    })));
    const { user } = await renderDetail();
    await user.click(screen.getByRole("button", { name: /查看完整证据/ }));
    const dialog = await screen.findByRole("dialog", { name: "这条记忆保存的完整证据" });

    // 段一同一分钟 → 单时间无范围分隔符；段二跨分钟 → 紧凑范围含分隔符；不写死日期。
    expect(within(dialog).getByText(/第 1 段 · 2 条消息/).textContent).not.toContain("–");
    expect(within(dialog).getByText(/第 2 段 · 2 条消息/).textContent).toContain("–");
    expect(dialog.textContent).not.toContain("2026-08-14");
    expect(dialog.textContent).not.toContain("2026-08-12");
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

describe("永久删除原型接线", () => {
  async function openDeleteDrawer(client: QueryClient = queryClientFactory()) {
    const user = userEvent.setup();
    render(<App client={client} />);
    await user.click(await screen.findByRole("button", { name: /暖灰的规范正文/ }));
    await screen.findByText((content, element) =>
      element?.classList.contains("detail-body") === true && content.includes("第二行仍属于当前版本。"));
    await user.click(screen.getByRole("button", { name: "永久删除" }));
    return { user, client };
  }

  function previewWith(members: Array<{ ordinal: number; memberKind: string; disposition: string }>) {
    return {
      ...previewResponse,
      closureMembers: members.map((member) => ({
        ordinal: member.ordinal,
        memberKind: member.memberKind,
        targetId: `${String(member.ordinal).padStart(8, "0")}-0000-4000-8000-${String(member.ordinal).padStart(12, "0")}`,
        disposition: member.disposition,
      })),
    };
  }

  it("点击前删除请求为 0，点击永久删除后 preview 恰 1 次", async () => {
    const { user } = await renderDetail();
    expect(deletionPreviewRequests).toBe(0);
    expect(deletionConfirmRequests).toBe(0);
    expect(deletionRunRequests).toBe(0);

    await user.click(screen.getByRole("button", { name: "永久删除" }));
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/完整证据：1 段，共 2 条消息/)).toBeVisible();
    expect(deletionPreviewRequests).toBe(1);
    expect(deletionConfirmRequests).toBe(0);
    expect(deletionRunRequests).toBe(0);
  });

  it("preview key 在抽屉生命周期内稳定，重新打开换新 key", async () => {
    const { user } = await openDeleteDrawer();
    await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(previewIdempotencyKeys).toHaveLength(1);
    const firstKey = previewIdempotencyKeys[0];
    expect(firstKey).toBeTruthy();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "永久删除" }));
    await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(previewIdempotencyKeys).toHaveLength(2);
    expect(previewIdempotencyKeys[1]).toBeTruthy();
    expect(previewIdempotencyKeys[1]).not.toBe(firstKey);
  });

  it("关闭再打开：confirm key 也换新", async () => {
    server.use(http.get("*/v1/deletion-runs/:runId", () =>
      HttpResponse.json({ ...runResponse, phase: "FINAL_FAILED", failureCode: "DELETION_EXECUTION_FAILED", retryable: false }),
    ));
    const { user } = await openDeleteDrawer();
    let dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await within(dialog).findByText(/永久删除执行失败/);
    expect(confirmIdempotencyKeys).toHaveLength(1);

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "永久删除" }));
    dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await within(dialog).findByText(/永久删除执行失败/);
    expect(confirmIdempotencyKeys).toHaveLength(2);
    expect(confirmIdempotencyKeys[1]).not.toBe(confirmIdempotencyKeys[0]);
  });

  it("删除抽屉展示完整证据与删除后果，不出现技术名词/枚举/objectRef", async () => {
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/完整证据：1 段，共 2 条消息/)).toBeVisible();
    expect(within(dialog).getByText(evidenceBodies[0])).toBeVisible();
    expect(within(dialog).getByText(evidenceBodies[1])).toBeVisible();
    expect(within(dialog).getByText("hide")).toBeVisible();
    expect(within(dialog).getByText("小林")).toBeVisible();
    expect(await within(dialog).findByText("删除后会清除 · 仅当前记忆使用")).toBeVisible();
    const text = dialog.textContent ?? "";
    expect(text).not.toContain("MEMORY");
    expect(text).not.toContain("SOURCE_ANCHOR");
    expect(text).not.toContain("SOURCE_UNIT");
    expect(text).not.toContain("SOURCE_PAYLOAD");
    expect(text).not.toContain("DELETE_REQUESTED");
    expect(text).not.toContain("RETAIN_SHARED");
    expect(text).not.toContain("AFFECTED_PENDING_CHOICE");
    expect(text).not.toContain("objectRef");
    expect(text).not.toContain("来源锚点");
    expect(text).not.toContain("来源消息");
    expect(text).not.toContain("原文载荷");
    expect(text).not.toContain("ab".repeat(32));
    expect(text).not.toContain(memoryId);
    expect(text).not.toContain(revisionId);
  });

  it("删除预览段数由真实 anchorId 动态派生，多段不串段", async () => {
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json({
      ...previewResponse,
      evidence: [
        {
          anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
          ordinal: 1,
          actorId: "44444444-4444-4444-8444-444444444444",
          actorStableRef: "a-1",
          displayLabel: "hide",
          occurredAt: "2026-08-12T09:30:01Z",
          bodyText: "删除段一消息",
          sharedByMemoryIds: [],
        },
        {
          anchorId: "99999999-bbbb-4bbb-8bbb-999999999999",
          ordinal: 2,
          actorId: "33333333-3333-4333-8333-333333333333",
          actorStableRef: "a-2",
          displayLabel: "小林",
          occurredAt: "2026-08-12T09:30:02Z",
          bodyText: "删除段二消息",
          sharedByMemoryIds: [],
        },
      ],
    })));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/完整证据：2 段，共 2 条消息/)).toBeVisible();

    const segmentOne = within(dialog).getByLabelText("第 1 段证据");
    const segmentTwo = within(dialog).getByLabelText("第 2 段证据");
    expect(within(segmentOne).getByText("删除段一消息")).toBeVisible();
    expect(within(segmentOne).queryByText("删除段二消息")).not.toBeInTheDocument();
    expect(within(segmentTwo).getByText("删除段二消息")).toBeVisible();
    expect(within(segmentTwo).queryByText("删除段一消息")).not.toBeInTheDocument();
  });

  it("共享段显示会保留及共享记忆标题，汇总注明保留段", async () => {
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json({
      ...previewResponse,
      evidence: previewResponse.evidence.map((item) => ({
        ...item,
        sharedByMemoryIds: ["77777777-7777-4777-8777-777777777777"],
      })),
      sharedMemories: [{ memoryId: "77777777-7777-4777-8777-777777777777", revisionNo: 1, title: "另一条保留记忆" }],
    })));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/其中 1 段保留/)).toBeVisible();
    expect(within(dialog).getByText("删除后会保留 · 另有 1 条记忆使用")).toBeVisible();
    expect(within(dialog).getByText("《另一条保留记忆》")).toBeVisible();
  });

  it("两条不同 memoryId 相同标题稳定同展，不产生 duplicate-key console error", async () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const sharedA = "77777777-7777-4777-8777-777777777777";
    const sharedB = "88888888-8888-4888-8888-888888888888";
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json({
      ...previewResponse,
      evidence: previewResponse.evidence.map((item) => ({
        ...item,
        sharedByMemoryIds: [sharedA, sharedB],
      })),
      sharedMemories: [
        { memoryId: sharedA, revisionNo: 1, title: "同名记忆" },
        { memoryId: sharedB, revisionNo: 1, title: "同名记忆" },
      ],
    })));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText("删除后会保留 · 另有 2 条记忆使用")).toBeVisible();
    expect(within(dialog).getAllByText("《同名记忆》")).toHaveLength(2);
    expect(errorSpy.mock.calls.some((args) => String(args[0]).includes("same key"))).toBe(false);
    errorSpy.mockRestore();
  });

  it("两个分散段分别保留与清除，共享标题不串段", async () => {
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json({
      ...previewResponse,
      evidence: [
        {
          anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
          ordinal: 1,
          actorId: "44444444-4444-4444-8444-444444444444",
          actorStableRef: "a-1",
          displayLabel: "hide",
          occurredAt: "2026-08-12T09:30:01Z",
          bodyText: "共享段消息",
          sharedByMemoryIds: ["77777777-7777-4777-8777-777777777777"],
        },
        {
          anchorId: "99999999-bbbb-4bbb-8bbb-999999999999",
          ordinal: 2,
          actorId: "33333333-3333-4333-8333-333333333333",
          actorStableRef: "a-2",
          displayLabel: "小林",
          occurredAt: "2026-08-12T09:30:02Z",
          bodyText: "独占段消息",
          sharedByMemoryIds: [],
        },
      ],
      sharedMemories: [{ memoryId: "77777777-7777-4777-8777-777777777777", revisionNo: 1, title: "另一条保留记忆" }],
    })));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/其中 1 段保留，1 段清除/)).toBeVisible();
    const segmentOne = within(dialog).getByLabelText("第 1 段证据");
    const segmentTwo = within(dialog).getByLabelText("第 2 段证据");
    expect(within(segmentOne).getByText("删除后会保留 · 另有 1 条记忆使用")).toBeVisible();
    expect(within(segmentOne).getByText("《另一条保留记忆》")).toBeVisible();
    expect(within(segmentTwo).getByText("删除后会清除 · 仅当前记忆使用")).toBeVisible();
    expect(within(segmentTwo).queryByText("《另一条保留记忆》")).not.toBeInTheDocument();
  });

  it("同一段部分共享时显示部分保留，消息级标记准确", async () => {
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json({
      ...previewResponse,
      evidence: [
        {
          anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
          ordinal: 1,
          actorId: "44444444-4444-4444-8444-444444444444",
          actorStableRef: "a-1",
          displayLabel: "hide",
          occurredAt: "2026-08-12T09:30:01Z",
          bodyText: "保留消息",
          sharedByMemoryIds: ["77777777-7777-4777-8777-777777777777"],
        },
        {
          anchorId: "99999999-aaaa-4aaa-8aaa-999999999999",
          ordinal: 2,
          actorId: "33333333-3333-4333-8333-333333333333",
          actorStableRef: "a-2",
          displayLabel: "小林",
          occurredAt: "2026-08-12T09:30:02Z",
          bodyText: "清除消息",
          sharedByMemoryIds: [],
        },
      ],
      sharedMemories: [{ memoryId: "77777777-7777-4777-8777-777777777777", revisionNo: 1, title: "另一条保留记忆" }],
    })));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText(/其中 1 段部分保留/)).toBeVisible();
    expect(within(dialog).getByText("删除后部分保留")).toBeVisible();
    const retainedBubble = within(dialog).getByText("保留消息").closest("article") as HTMLElement;
    expect(within(retainedBubble).getByText(/会保留/)).toBeVisible();
    expect(within(retainedBubble).getByText(/《另一条保留记忆》/)).toBeVisible();
    const clearedBubble = within(dialog).getByText("清除消息").closest("article") as HTMLElement;
    expect(within(clearedBubble).getByText("会清除")).toBeVisible();
  });

  it.each([
    ["空闭包", []],
    ["重复 ordinal", [
      { ordinal: 1, memberKind: "MEMORY", disposition: "DELETE_REQUESTED" },
      { ordinal: 1, memberKind: "MEMORY_REVISION", disposition: "DELETE_REQUESTED" },
    ]],
    ["不连续 ordinal", [
      { ordinal: 1, memberKind: "MEMORY", disposition: "DELETE_REQUESTED" },
      { ordinal: 3, memberKind: "MEMORY_REVISION", disposition: "DELETE_REQUESTED" },
    ]],
    ["未知 memberKind", [
      { ordinal: 1, memberKind: "UNKNOWN_KIND", disposition: "DELETE_REQUESTED" },
    ]],
    ["未知 disposition", [
      { ordinal: 1, memberKind: "MEMORY", disposition: "UNKNOWN_DISPOSITION" },
    ]],
    ["未知 memberKind SHARED_REFERENCE 缺失", [
      { ordinal: 1, memberKind: "MEMORY", disposition: "DELETE_REQUESTED" },
      { ordinal: 2, memberKind: "AFFECTED_MEMORY", disposition: "DELETE_REQUESTED" },
    ]],
  ])("%s 时禁用最终确认并显示稳定说明", async (_name, members) => {
    server.use(http.post("*/v1/deletion-previews", () => HttpResponse.json(previewWith(members))));
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    const confirm = within(dialog).getByRole("button", { name: "永久删除这 1 条记忆" });
    await waitFor(() => expect(confirm).toBeDisabled());
    expect(within(dialog).getByRole("status")).toBeVisible();
  });

  it("关闭/Escape 不发送 confirm，焦点回到永久删除", async () => {
    const { user } = await openDeleteDrawer();
    await screen.findByRole("dialog", { name: "永久删除影响预览" });
    const trigger = screen.getByRole("button", { name: "永久删除" });
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
    expect(deletionConfirmRequests).toBe(0);
  });

  it("confirm 双击仅产生一个在途确认，并复用同一 confirm key", async () => {
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    const confirm = await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" });
    await user.click(confirm);
    await user.click(confirm);
    expect(deletionConfirmRequests).toBe(1);
    expect(confirmIdempotencyKeys).toHaveLength(1);
    expect(confirmIdempotencyKeys[0]).toBeTruthy();
  });

  it("statusUrl 非同源或路径错误时 fail closed 且不轮询", async () => {
    server.use(
      http.post("*/v1/deletion-previews/:id/confirm", ({ request }) => {
        deletionConfirmRequests += 1;
        confirmIdempotencyKeys.push(request.headers.get("idempotency-key") ?? "");
        return HttpResponse.json({ ...confirmResponse, statusUrl: "http://evil.example/v1/deletion-runs/" + runId }, { status: 202 });
      }),
    );
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    expect(await within(dialog).findByText(/永久删除执行失败/)).toBeVisible();
    expect(deletionRunRequests).toBe(0);
  });

  it("成功后自动返回列表并清除 detail/evidence 缓存与 DOM 正文", async () => {
    let poll = 0;
    let listRequests = 0;
    let deleted = false;
    server.use(
      http.get("*/v1/deletion-runs/:runId", () => {
        poll += 1;
        if (poll >= 2) deleted = true;
        return HttpResponse.json(poll === 1 ? { ...runResponse, phase: "RECEIVED" } : runResponse);
      }),
      http.get("*/v1/memories", () => {
        listRequests += 1;
        return HttpResponse.json(deleted ? { ...listResponse, items: [] } : listResponse);
      }),
    );
    const { user, client } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));

    // auto-navigate back to list without any further click
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(window.location.pathname).toBe("/memories");
    await waitFor(() => expect(screen.queryByText("第二行仍属于当前版本。")).not.toBeInTheDocument());

    // detail/evidence cache cleared; list refetched
    await waitFor(() => expect(client.getQueryData(["local-v1-memory", memoryId])).toBeUndefined());
    await waitFor(() => expect(client.getQueriesData({ queryKey: [EVIDENCE_KEY, memoryId] })).toHaveLength(0));
    expect(listRequests).toBeGreaterThanOrEqual(2);
    expect(poll).toBeGreaterThanOrEqual(2);
  });

  it("关闭后迟到的 preview 响应不重新显示闭包或成功状态", async () => {
    let resolvePreview: ((value: Response) => void) | null = null;
    server.use(http.post("*/v1/deletion-previews", () => new Promise<Response>((resolve) => { resolvePreview = resolve; })));
    const { user } = await openDeleteDrawer();
    await screen.findByRole("dialog", { name: "永久删除影响预览" });

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    resolvePreview!(HttpResponse.json(previewResponse));
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(screen.queryByText(/完整证据：1 段，共 2 条消息/)).not.toBeInTheDocument();
    expect(screen.queryByText("永久删除这 1 条记忆")).not.toBeInTheDocument();
  });

  it("polling 期间关闭后，迟到终态不触发导航或缓存清理", async () => {
    let resolveRun: ((value: Response) => void) | null = null;
    server.use(http.get("*/v1/deletion-runs/:runId", () => new Promise<Response>((resolve) => { resolveRun = resolve; })));
    const { user, client } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    resolveRun!(HttpResponse.json(runResponse));
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(window.location.pathname).toContain(memoryId);
    expect(client.getQueryData(["local-v1-memory", memoryId])).toBeDefined();
  });

  it("FINAL_FAILED 显示稳定中文失败，不宣称删除完成", async () => {
    server.use(
      http.get("*/v1/deletion-runs/:runId", () =>
        HttpResponse.json({ ...runResponse, phase: "FINAL_FAILED", failureCode: "DELETION_EXECUTION_FAILED", retryable: false }),
      ),
    );
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    expect(await within(dialog).findByText(/永久删除执行失败/)).toBeVisible();
    expect(within(dialog).getByText(/不可重试/)).toBeVisible();
    expect(within(dialog).queryByText("永久删除已完成")).not.toBeInTheDocument();
    expect(dialog.textContent).not.toContain("SQL");
    expect(dialog.textContent).not.toContain("stack");
  });

  it("轮询超时显示尚未收敛，不伪造失败或成功", async () => {
    server.use(http.get("*/v1/deletion-runs/:runId", () => HttpResponse.json({ ...runResponse, phase: "RECEIVED" })));
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    expect(await within(dialog).findByText(/执行状态尚未收敛/, {}, { timeout: 10000 })).toBeVisible();
    expect(within(dialog).queryByText("永久删除已完成")).not.toBeInTheDocument();
  }, 15000);

  it("stale/denied/offline 准确分类，0 原始异常泄漏", async () => {
    server.use(
      http.post("*/v1/deletion-previews", () =>
        new HttpResponse(JSON.stringify({ type: "about:blank", title: "x", status: 409, requestId: "req", resultCategory: "STALE", failureCode: "DELETION_PREVIEW_STALE", retryable: false }), {
          status: 409,
          headers: { "Content-Type": "application/problem+json" },
        }),
      ),
    );
    await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    expect(await within(dialog).findByText("页面中的版本已经过期")).toBeVisible();
    expect(dialog.textContent).not.toContain("DELETION_PREVIEW_STALE");
    expect(dialog.textContent).not.toContain("stack");
  });

  it("浏览器不发送 Authorization/Cookie/X-Action-Capability，且 storage 写入为 0", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(deletionAuthHeaders.length).toBeGreaterThan(0);
    for (const header of deletionAuthHeaders) expect(header).toBeNull();
    for (const header of deletionCapabilityHeaders) expect(header).toBeNull();
    expect(setItem).not.toHaveBeenCalled();
    expect("indexedDB" in window).toBe(false);
    setItem.mockRestore();
  });

  it("延迟 list refetch 反证：成功后立即移除旧行，成功反馈可见", async () => {
    let poll = 0;
    let listCalls = 0;
    let releaseRefetch: ((value: Response) => void) | null = null;
    server.use(
      http.get("*/v1/deletion-runs/:runId", () => {
        poll += 1;
        return HttpResponse.json(poll === 1 ? { ...runResponse, phase: "RECEIVED" } : runResponse);
      }),
      http.get("*/v1/memories", () => {
        listCalls += 1;
        if (listCalls === 1) return HttpResponse.json(listResponse);
        // Hold the post-deletion refetch open so the cache window is observable.
        return new Promise<Response>((resolve) => { releaseRefetch = resolve; });
      }),
    );
    const { user, client } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(window.location.pathname).toBe("/memories");
    const feedback = screen.getByText("已永久删除 1 条记忆。");
    expect(feedback).toBeVisible();
    expect(feedback.closest("[role=status]")).not.toBeNull();
    expect(feedback.closest("[aria-live=polite]")).not.toBeNull();

    // While the refetch is still held open, the deleted row is already gone from cache and DOM.
    const listEntries = client.getQueriesData<ListCache>({ queryKey: ["local-v1-memories"] });
    expect(listEntries.length).toBeGreaterThan(0);
    for (const [, data] of listEntries) {
      if (data && Array.isArray(data.pages)) {
        for (const page of data.pages) {
          expect(page.items.map((item) => item.memoryId)).not.toContain(memoryId);
        }
      }
    }
    expect(screen.queryByRole("button", { name: /暖灰的规范正文/ })).not.toBeInTheDocument();

    releaseRefetch!(HttpResponse.json({ ...listResponse, items: [] }));
  });

  it("refetch 失败反证：旧行不复活，成功反馈仍说明删除完成", async () => {
    let poll = 0;
    let listCalls = 0;
    server.use(
      http.get("*/v1/deletion-runs/:runId", () => {
        poll += 1;
        return HttpResponse.json(poll === 1 ? { ...runResponse, phase: "RECEIVED" } : runResponse);
      }),
      http.get("*/v1/memories", () => {
        listCalls += 1;
        if (listCalls === 1) return HttpResponse.json(listResponse);
        return new HttpResponse(null, { status: 503 });
      }),
    );
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    // The deleted row must not resurrect even though the list refetch failed.
    expect(screen.queryByRole("button", { name: /暖灰的规范正文/ })).not.toBeInTheDocument();
    // The one-shot feedback still states the deletion completed.
    expect(screen.getByText("已永久删除 1 条记忆。")).toBeVisible();
    // List failure is presented via the existing safe error path.
    expect(await screen.findByText("读取完整性检查没有通过")).toBeVisible();
  });

  it.each([
    ["FINAL_FAILED", { ...runResponse, phase: "FINAL_FAILED", failureCode: "DELETION_EXECUTION_FAILED", retryable: false }],
    ["STALE", { ...runResponse, phase: "FINAL_FAILED", failureCode: "DELETION_EXECUTION_STALE", retryable: false }],
  ])("失败终态 %s 不清场：无成功反馈、无缓存移除、无导航", async (_label, run) => {
    server.use(http.get("*/v1/deletion-runs/:runId", () => HttpResponse.json(run)));
    const { user, client } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await within(dialog).findByText(/永久删除执行失败/);

    expect(screen.queryByText("已永久删除 1 条记忆。")).not.toBeInTheDocument();
    expect(window.location.pathname).toContain(memoryId);
    expect(client.getQueryData(["local-v1-memory", memoryId])).toBeDefined();
    const listData = client.getQueryData<ListCache>(["local-v1-memories", "", "ALL", "ALL"]);
    expect(listData?.pages.flatMap((page) => page.items.map((item) => item.memoryId))).toContain(memoryId);
  });

  it("轮询超时不清场：无成功反馈、无缓存移除、无导航", async () => {
    server.use(http.get("*/v1/deletion-runs/:runId", () => HttpResponse.json({ ...runResponse, phase: "RECEIVED" })));
    const { user, client } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await within(dialog).findByText(/执行状态尚未收敛/, {}, { timeout: 10000 });

    expect(screen.queryByText("已永久删除 1 条记忆。")).not.toBeInTheDocument();
    expect(window.location.pathname).toContain(memoryId);
    expect(client.getQueryData(["local-v1-memory", memoryId])).toBeDefined();
    const listData = client.getQueryData<ListCache>(["local-v1-memories", "", "ALL", "ALL"]);
    expect(listData?.pages.flatMap((page) => page.items.map((item) => item.memoryId))).toContain(memoryId);
  }, 15000);

  it("一次性反馈：重新选择其他记忆或普通 refetch 不重复，且不写持久存储", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const memoryB = "99999999-9999-4999-8999-9999999999bb";
    let poll = 0;
    let deleted = false;
    server.use(
      http.get("*/v1/deletion-runs/:runId", () => {
        poll += 1;
        if (poll >= 2) deleted = true;
        return HttpResponse.json(poll === 1 ? { ...runResponse, phase: "RECEIVED" } : runResponse);
      }),
      http.get("*/v1/memories", () => HttpResponse.json(deleted ? {
        ...listResponse,
        items: [{
          memoryId: memoryB,
          currentRevisionId: revisionId,
          revisionNo: 1,
          state: "ACTIVE",
          isolated: false,
          memoryType: "EVENT",
          perspective: "xiaolin:33333333-3333-4333-8333-333333333333",
          title: "另一条记忆",
          summary: "仍保留的条目",
          sourceAvailability: "AVAILABLE",
          uncertaintyCode: "CONFIRMED",
          updatedAt: "2026-08-12T09:00:00Z",
        }],
      } : listResponse)),
    );
    const { user } = await openDeleteDrawer();
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByText("已永久删除 1 条记忆。")).toBeVisible();

    // A normal refetch converging on the server truth must not duplicate the feedback.
    await screen.findByText("另一条记忆");
    expect(screen.getAllByText("已永久删除 1 条记忆。")).toHaveLength(1);

    // Selecting another memory clears the one-shot feedback instead of replaying it.
    await user.click(screen.getByRole("button", { name: /另一条记忆/ }));
    expect(screen.queryByText("已永久删除 1 条记忆。")).not.toBeInTheDocument();
    expect(setItem).not.toHaveBeenCalled();
    expect("indexedDB" in window).toBe(false);
    setItem.mockRestore();
  });
});

describe("记忆列表稳定分页与类型筛选", () => {
  function item(id: string, title: string, type = "EVENT") {
    return {
      memoryId: id,
      currentRevisionId: revisionId,
      revisionNo: 1,
      state: "ACTIVE",
      isolated: false,
      memoryType: type,
      perspective: "xiaolin:33333333-3333-4333-8333-333333333333",
      title,
      summary: `${title} 的摘要`,
      sourceAvailability: "AVAILABLE",
      uncertaintyCode: "CONFIRMED",
      updatedAt: "2026-08-12T09:00:00Z",
    } as const;
  }

  const typeTrigger = () => screen.getByRole("button", { name: /按记忆类型筛选/ });

  async function chooseType(user: ReturnType<typeof userEvent.setup>, label: string) {
    await user.click(typeTrigger());
    await user.click(await screen.findByRole("radio", { name: label }));
  }

  it("首屏只请求 limit=30 且不带 cursor，收到 nextCursor 后显示加载更多", async () => {
    const requests: Array<{ cursor: string | null; limit: number | null }> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      const url = new URL(request.url);
      requests.push({
        cursor: url.searchParams.get("cursor"),
        limit: url.searchParams.get("limit") === null ? null : Number(url.searchParams.get("limit")),
      });
      return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: "cursor-1" });
    }));
    render(<App client={queryClientFactory()} />);
    expect(await screen.findByRole("button", { name: "加载更多" })).toBeVisible();
    expect(requests[0]).toEqual({ cursor: null, limit: 30 });
    expect(screen.getByText("已加载 1 条")).toBeVisible();
  });

  it("连续加载 30+30+10，cursor 逐字传递，末页按钮消失且显示共 N 条", async () => {
    const all = Array.from({ length: 70 }, (_, i) => item(`id-${i + 1}`, `分页记忆 ${i + 1}`));
    let calls = 0;
    const cursors: Array<string | null> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      const url = new URL(request.url);
      cursors.push(url.searchParams.get("cursor"));
      const start = calls * 30;
      const items = all.slice(start, start + 30);
      calls += 1;
      const nextCursor = start + 30 < all.length ? `cursor-${calls}` : undefined;
      return HttpResponse.json({ ...listResponse, items, ...(nextCursor ? { nextCursor } : {}) });
    }));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await screen.findByRole("button", { name: "加载更多" });
    await user.click(screen.getByRole("button", { name: "加载更多" }));
    await screen.findByText("已加载 60 条");
    await user.click(screen.getByRole("button", { name: "加载更多" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "加载更多" })).not.toBeInTheDocument());
    expect(cursors).toEqual([null, "cursor-1", "cursor-2"]);
    expect(screen.getAllByRole("button", { name: /分页记忆/ })).toHaveLength(70);
    expect(screen.getByText("共 70 条")).toBeVisible();
  });

  it("双击加载更多只产生一个在途下一页请求", async () => {
    let pageCalls = 0;
    server.use(http.get("*/v1/memories", async ({ request }) => {
      const url = new URL(request.url);
      if (url.searchParams.get("cursor")) {
        pageCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 60));
        return HttpResponse.json({ ...listResponse, items: [item("id-2", "第二页")], nextCursor: undefined });
      }
      return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: "cursor-1" });
    }));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await user.dblClick(await screen.findByRole("button", { name: "加载更多" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "加载更多" })).not.toBeInTheDocument());
    expect(pageCalls).toBe(1);
  });

  it("下一页失败保留首屏 30 条，重试只重试下一页并成功追加", async () => {
    let pageCalls = 0;
    server.use(http.get("*/v1/memories", ({ request }) => {
      const url = new URL(request.url);
      if (!url.searchParams.get("cursor")) {
        return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: "cursor-1" });
      }
      pageCalls += 1;
      if (pageCalls === 1) return new HttpResponse(null, { status: 503 });
      return HttpResponse.json({ ...listResponse, items: [item("id-2", "第二页记忆"), item("id-3", "第三页记忆")], nextCursor: undefined });
    }));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await user.click(await screen.findByRole("button", { name: "加载更多" }));
    const retry = await screen.findByRole("button", { name: "加载更多失败，请重试" });
    expect(screen.getByRole("button", { name: /暖灰的规范正文/ })).toBeVisible();
    await user.click(retry);
    await waitFor(() => expect(screen.queryByRole("button", { name: "加载更多失败，请重试" })).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: /第二页记忆/ })).toBeVisible();
    expect(screen.getByRole("button", { name: /第三页记忆/ })).toBeVisible();
  });

  it("类型筛选发送精确 wire enum，全部类型省略 memoryType 参数", async () => {
    const paramsList: Array<URLSearchParams> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      paramsList.push(new URL(request.url).searchParams);
      return HttpResponse.json({ ...listResponse, items: [], nextCursor: undefined });
    }));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await screen.findByText("共 0 条");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    await chooseType(user, "事件");
    await waitFor(() => expect(paramsList[paramsList.length - 1].get("memoryType")).toBe("EVENT"));
    await chooseType(user, "全部类型");
    await waitFor(() => expect(paramsList[paramsList.length - 1].get("memoryType")).toBeNull());
  });

  it("纸张菜单支持触发、方向键选择、Escape 和外部关闭", async () => {
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    const trigger = typeTrigger();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const all = await screen.findByRole("radio", { name: "全部类型" });
    expect(all).toHaveAttribute("aria-checked", "true");
    expect(all).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("radio", { name: "事件" })).toHaveFocus();
    await user.keyboard("{Enter}");
    await waitFor(() => expect(trigger).toHaveAttribute("aria-expanded", "false"));
    expect(trigger).toHaveAccessibleName("按记忆类型筛选，当前事件");

    await user.keyboard("{ArrowDown}");
    expect(await screen.findByRole("radio", { name: "事件" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "false");

    await user.click(trigger);
    await user.click(screen.getByPlaceholderText("搜索当前规范记忆"));
    expect(screen.queryByRole("radiogroup", { name: "按记忆类型筛选" })).not.toBeInTheDocument();
    expect(trigger).toHaveAccessibleName("按记忆类型筛选，当前事件");
  });

  it("切换类型重置为第一页并把 type 写入 URL；非法 URL type 回退全部并清除", async () => {
    window.history.replaceState({}, "", "/memories");
    let calls = 0;
    server.use(http.get("*/v1/memories", ({ request }) => {
      const url = new URL(request.url);
      calls += 1;
      const cursor = url.searchParams.get("cursor");
      return HttpResponse.json({
        ...listResponse,
        items: [item("id-1", cursor ? "第二页记忆" : "暖灰的规范正文")],
        nextCursor: cursor ? undefined : "cursor-1",
      });
    }));
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await screen.findByRole("button", { name: "加载更多" });
    const before = calls;
    await chooseType(user, "判断");
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBe("CLAIM"));
    expect(calls).toBeGreaterThan(before);
    expect(screen.getByRole("button", { name: /暖灰的规范正文/ })).toBeVisible();
  });

  it("非法 URL type 回退全部类型并从 URL 清除，不向 API 发送非法值", async () => {
    window.history.replaceState({}, "", "/memories?type=EVIL");
    const sent: Array<string | null> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      sent.push(new URL(request.url).searchParams.get("memoryType"));
      return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: undefined });
    }));
    render(<App client={queryClientFactory()} />);
    await screen.findByText("共 1 条");
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBeNull());
    expect(sent.every((value) => value === null)).toBe(true);
    expect(typeTrigger()).toHaveAccessibleName("按记忆类型筛选，当前全部");
  });

  it("挂载后导航到非法 type 时动态回退并清除，合法 type 保留", async () => {
    window.history.replaceState({}, "", "/memories");
    const sent: Array<string | null> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      sent.push(new URL(request.url).searchParams.get("memoryType"));
      return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: undefined });
    }));
    render(<App client={queryClientFactory()} />);
    await screen.findByText("共 1 条");

    // A navigation (pushState + popstate) into an invalid type must be cleared dynamically.
    window.history.pushState({}, "", "/memories?type=EVIL");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBeNull());
    expect(sent.every((value) => value === null)).toBe(true);

    // A subsequent valid type is preserved, not cleared.
    window.history.pushState({}, "", "/memories?type=CLAIM");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBe("CLAIM"));
    expect(typeTrigger()).toHaveAccessibleName("按记忆类型筛选，当前判断");
  });

  it("浏览器 back 到非法 type 时动态回退并清除", async () => {
    window.history.replaceState({}, "", "/memories?type=CLAIM");
    const sent: Array<string | null> = [];
    server.use(http.get("*/v1/memories", ({ request }) => {
      sent.push(new URL(request.url).searchParams.get("memoryType"));
      return HttpResponse.json({ ...listResponse, items: [item("id-1", "暖灰的规范正文")], nextCursor: undefined });
    }));
    render(<App client={queryClientFactory()} />);
    await screen.findByText("共 1 条");
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBe("CLAIM"));

    // Navigate away to a valid URL, then back (popstate) into the CLAIM entry — must stay valid.
    window.history.pushState({}, "", "/memories");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBeNull());
    window.history.back();
    await new Promise((resolve) => setTimeout(resolve, 50));
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => expect(new URLSearchParams(window.location.search).get("type")).toBe("CLAIM"));
  });

  it("删除已加载页中的项目立即从所有页缓存移除且反馈只出现一次", async () => {
    let deleted = false;
    server.use(
      http.post("*/v1/deletion-previews/:id/confirm", () => {
        deleted = true;
        return HttpResponse.json(confirmResponse, { status: 202 });
      }),
      http.get("*/v1/deletion-runs/:runId", () => HttpResponse.json(runResponse)),
      http.get("*/v1/memories", ({ request }) => {
        const url = new URL(request.url);
        if (!url.searchParams.get("cursor")) {
          const items = deleted
            ? [item("id-x", "第二记忆")]
            : [item(memoryId, "暖灰的规范正文"), item("id-x", "第二记忆")];
          return HttpResponse.json({ ...listResponse, items, nextCursor: "cursor-1" });
        }
        return HttpResponse.json({ ...listResponse, items: [item("id-y", "第三记忆")], nextCursor: undefined });
      }),
    );
    const user = userEvent.setup();
    render(<App client={queryClientFactory()} />);
    await user.click(await screen.findByRole("button", { name: "加载更多" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "加载更多" })).not.toBeInTheDocument());
    // Open the detail of the page-1 item (global memoryId) and delete it.
    await user.click(screen.getByRole("button", { name: /暖灰的规范正文/ }));
    await screen.findByText((content, element) =>
      element?.classList.contains("detail-body") === true && content.includes("第二行仍属于当前版本。"));
    await user.click(screen.getByRole("button", { name: "永久删除" }));
    const dialog = await screen.findByRole("dialog", { name: "永久删除影响预览" });
    await user.click(await within(dialog).findByRole("button", { name: "永久删除这 1 条记忆" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByText("已永久删除 1 条记忆。")).toBeVisible();
    expect(screen.getAllByText("已永久删除 1 条记忆。")).toHaveLength(1);
    // Removed everywhere immediately (not just the page it was on); siblings on other pages survive.
    expect(screen.queryByRole("button", { name: /暖灰的规范正文/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /第二记忆/ })).toBeVisible();
    expect(screen.getByRole("button", { name: /第三记忆/ })).toBeVisible();
  });
});
