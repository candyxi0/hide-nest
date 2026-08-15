import * as Dialog from "@radix-ui/react-dialog";
import {
  Configuration,
  DeletionApi,
  FetchError,
  MemoriesApi,
  MemoryState,
  ResponseError,
  type DeletionClosureMember,
  type DeletionPreviewResponse,
  type MemoryDetail,
  type MemoryEvidenceResponse,
  type MemoryListItem,
} from "@hide-nest/api-client-ts";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  BrowserRouter,
  Link,
  Navigate,
  Route,
  Routes,
  useLocation,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router";
import "./app.css";
import { deletionRequestHash } from "./deletion-canonicalizer";
import { EVIDENCE_KEY, queryClientFactory } from "./query";

const api = new MemoriesApi(new Configuration({ basePath: "/v1" }));
const deletionApi = new DeletionApi(new Configuration({ basePath: "/v1" }));
const LIST_KEY = "local-v1-memories";
const DETAIL_KEY = "local-v1-memory";

type MemoryFilter = "ALL" | "ACTIVE" | "ARCHIVED";
type ReadProblem = "offline" | "denied" | "not-found" | "invalid" | "integrity";

const memoryTypeLabels: Record<string, string> = {
  EVENT: "事件",
  CLAIM: "判断",
  QUOTE: "引述",
  INTERPRETATION: "理解",
  CALIBRATION: "校准",
  PRINCIPLE: "原则",
};

function classifyError(error: unknown): ReadProblem {
  if (error instanceof FetchError || error instanceof TypeError) return "offline";
  if (error instanceof ResponseError) {
    if (error.response.status === 401 || error.response.status === 403) return "denied";
    if (error.response.status === 404) return "not-found";
    if (error.response.status === 422) return "invalid";
  }
  return "integrity";
}

// ─── Permanent deletion (Task33B2) ───────────────────────────────────────────

const KNOWN_MEMBER_KINDS = new Set(["MEMORY", "MEMORY_REVISION", "SOURCE_ANCHOR", "SOURCE_UNIT", "SOURCE_PAYLOAD", "SHARED_REFERENCE"]);
const KNOWN_DISPOSITIONS = new Set(["DELETE_REQUESTED", "DELETE_CANDIDATE", "RETAIN_SHARED"]);

type DeleteProblem = "offline" | "denied" | "not-found" | "stale" | "integrity";

interface ClosureSummary {
  valid: boolean;
  reason: string;
}

function summarizeClosure(members: DeletionClosureMember[]): ClosureSummary {
  if (members.length === 0) {
    return { valid: false, reason: "系统没有返回可确认的闭合范围。" };
  }
  const seen = new Set<number>();
  for (const member of members) {
    if (!Number.isInteger(member.ordinal) || member.ordinal < 1) {
      return { valid: false, reason: "影响序号不连续，已暂停最终确认。" };
    }
    if (seen.has(member.ordinal)) {
      return { valid: false, reason: "影响序号重复，已暂停最终确认。" };
    }
    seen.add(member.ordinal);
  }
  const sorted = members.map((member) => member.ordinal).sort((a, b) => a - b);
  for (let index = 0; index < sorted.length; index += 1) {
    if (sorted[index] !== index + 1) {
      return { valid: false, reason: "影响序号不连续，已暂停最终确认。" };
    }
  }
  for (const member of members) {
    if (!KNOWN_MEMBER_KINDS.has(member.memberKind)) {
      return { valid: false, reason: "存在未知的闭合成员类型，已暂停最终确认。" };
    }
    if (!KNOWN_DISPOSITIONS.has(member.disposition)) {
      return { valid: false, reason: "存在未知的处置状态，已暂停最终确认。" };
    }
  }
  return { valid: true, reason: "" };
}

interface ProblemShape {
  resultCategory?: string;
  failureCode?: string;
  requestId?: string;
  retryable?: boolean;
}

async function readProblemBody(response: Response): Promise<ProblemShape | null> {
  try {
    const body = await response.clone().json();
    if (body && typeof body === "object") {
      const record = body as Record<string, unknown>;
      return {
        resultCategory: typeof record.resultCategory === "string" ? record.resultCategory : undefined,
        failureCode: typeof record.failureCode === "string" ? record.failureCode : undefined,
        requestId: typeof record.requestId === "string" ? record.requestId : undefined,
        retryable: typeof record.retryable === "boolean" ? record.retryable : undefined,
      };
    }
  } catch {
    // non-JSON problem body
  }
  return null;
}

async function classifyDeleteError(error: unknown): Promise<DeleteProblem> {
  if (error instanceof FetchError || error instanceof TypeError) return "offline";
  if (error instanceof ResponseError) {
    const status = error.response.status;
    const problem = await readProblemBody(error.response);
    if (problem && (problem.resultCategory === "STALE" || (problem.failureCode?.includes("STALE") ?? false))) {
      return "stale";
    }
    if (status === 401 || status === 403) return "denied";
    if (status === 404) return "not-found";
    if (status === 409 || status === 422) return "stale";
    return "integrity";
  }
  return "integrity";
}

const DELETE_PROBLEM_COPY: Record<DeleteProblem, [string, string]> = {
  offline: ["现在无法连接本地接入器", "预览没有发出；请确认本地服务可用后再试。"],
  denied: ["当前没有删除权限", "系统不会透露目标是否存在，也不会执行删除。"],
  "not-found": ["没有找到可删除的目标", "目标不存在、版本不匹配或已经进入删除围栏时，都使用同一安全结果。"],
  stale: ["页面中的版本已经过期", "旧预览不会应用到新版本；请刷新详情后重新预览。"],
  integrity: ["删除预览没有通过完整性检查", "依赖故障或载荷校验失败；页面不会伪造删除结果。"],
};

function resolveRun(statusUrl: string, runId: string): { ok: true } | { ok: false } {
  let url: URL;
  try {
    url = new URL(statusUrl, window.location.origin);
  } catch {
    return { ok: false };
  }
  if (url.origin !== window.location.origin) return { ok: false };
  if (url.pathname !== `/v1/deletion-runs/${runId}`) return { ok: false };
  return { ok: true };
}

function failureLabel(code: string | undefined): string {
  if (!code) return "执行失败";
  if (code.includes("CLOSURE")) return "删除闭合范围不一致";
  if (code.includes("STALE")) return "删除预览或版本已过期";
  if (code.includes("EXECUTION")) return "删除执行失败";
  if (code.includes("FENCED")) return "目标已进入删除围栏";
  return "执行失败";
}

function useDebouncedValue(value: string, wait = 250) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), wait);
    return () => window.clearTimeout(timer);
  }, [value, wait]);
  return debounced;
}

function AppShell() {
  return (
    <div className="app-shell">
      <div className="paper-grain" aria-hidden="true" />
      <aside className="side-rail">
        <Link className="wordmark" to="/memories" aria-label="nest 记忆档案">
          <span className="wordmark-dot" aria-hidden="true" />
          <span>nest</span>
        </Link>
        <p className="brand-note">和时间一起，安静地记住。</p>
        <nav aria-label="主要导航">
          <Link className="nav-item active" to="/memories" aria-label="记忆档案"><span aria-hidden="true">▤</span><span>记忆档案</span></Link>
          <span className="nav-item disabled"><span aria-hidden="true">⌁</span><span>待续确认</span><small>后续开放</small></span>
          <span className="nav-item disabled"><span aria-hidden="true">⌇</span><span>处理记录</span><small>后续开放</small></span>
          <span className="nav-item disabled"><span aria-hidden="true">○</span><span>系统状态</span><small>后续开放</small></span>
        </nav>
        <div className="connection-note">
          <span className="connection-dot" aria-hidden="true" />
          <strong>本机只读连接</strong>
          <p>浏览器不持有远端凭据</p>
        </div>
      </aside>
      <main className="main-stage">
        <header className="top-bar">
          <div>
            <span className="eyebrow">记忆档案</span>
            <h1>留下来的，不必喧哗</h1>
          </div>
          <span className="readonly-mark">LOCAL V1 · 只读</span>
        </header>
        <Routes>
          <Route path="/memories" element={<MemoryArchive />} />
          <Route path="/memories/:memoryId" element={<MemoryArchive />} />
          <Route path="/" element={<Navigate to="/memories" replace />} />
          <Route path="*" element={<Navigate to="/memories" replace />} />
        </Routes>
      </main>
    </div>
  );
}

function MemoryArchive() {
  const { memoryId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialQuery = searchParams.get("query") ?? "";
  const initialState = searchParams.get("state");
  const [query, setQuery] = useState(initialQuery);
  const filter: MemoryFilter = initialState === "ACTIVE" || initialState === "ARCHIVED" ? initialState : "ALL";
  const debouncedQuery = useDebouncedValue(query);

  useEffect(() => {
    const next = new URLSearchParams(searchParams);
    if (debouncedQuery.trim()) next.set("query", debouncedQuery.trim());
    else next.delete("query");
    setSearchParams(next, { replace: true });
  // searchParams is deliberately omitted: this effect owns only the query key.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQuery, setSearchParams]);

  const listQuery = useQuery({
    queryKey: [LIST_KEY, debouncedQuery.trim(), filter],
    queryFn: () => api.listMemories({
      query: debouncedQuery.trim() || undefined,
      state: filter === "ALL" ? undefined : MemoryState[filter === "ACTIVE" ? "Active" : "Archived"],
      limit: 30,
    }),
  });

  const selectFilter = (nextFilter: MemoryFilter) => {
    const next = new URLSearchParams(searchParams);
    if (nextFilter === "ALL") next.delete("state");
    else next.set("state", nextFilter);
    setSearchParams(next, { replace: true });
  };

  const selectMemory = (id: string) => navigate({ pathname: `/memories/${id}`, search: location.search });
  const backToList = () => navigate({ pathname: "/memories", search: location.search });

  return (
    <section className={`memory-route ${memoryId ? "detail-open" : ""}`} aria-label="记忆档案">
      <section className="archive-index" aria-label="记忆列表">
        <div className="index-tools">
          <label className="search-field">
            <span aria-hidden="true">⌕</span>
            <span className="sr-only">搜索记忆</span>
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索当前规范记忆"
              autoComplete="off"
            />
          </label>
          <div className="filter-row" role="group" aria-label="记忆状态筛选">
            {(["ALL", "ACTIVE", "ARCHIVED"] as const).map((value) => (
              <button
                key={value}
                type="button"
                className={filter === value ? "filter-chip active" : "filter-chip"}
                aria-pressed={filter === value}
                onClick={() => selectFilter(value)}
              >
                {value === "ALL" ? "全部" : value === "ACTIVE" ? "有效" : "归档"}
              </button>
            ))}
          </div>
        </div>
        <div className="index-meta" aria-live="polite">
          <span>{listQuery.data ? `${listQuery.data.items.length} 条档案` : "读取中"}</span>
          <span>最近变化</span>
        </div>
        <ListResult
          query={debouncedQuery.trim()}
          selectedId={memoryId}
          result={listQuery}
          onSelect={selectMemory}
        />
      </section>
      <article className="memory-detail" aria-live="polite">
        {memoryId ? (
          <MemoryDetailView memoryId={memoryId} onBack={backToList} />
        ) : (
          <div className="welcome-detail">
            <span className="eyebrow">只读档案</span>
            <h2>选择一条记忆，看看它现在留下了什么。</h2>
            <p>正文、版本与来源边界都来自当前规范事实；页面不会在本地另存一份。</p>
          </div>
        )}
      </article>
    </section>
  );
}

type ListQuery = ReturnType<typeof useQuery<{ items: MemoryListItem[] }>>;

function ListResult({
  query,
  selectedId,
  result,
  onSelect,
}: {
  query: string;
  selectedId?: string;
  result: ListQuery;
  onSelect: (id: string) => void;
}) {
  if (result.isPending) return <LoadingState scope="列表" />;
  if (result.isError) return <ProblemPanel problem={classifyError(result.error)} />;
  if (!result.data.items.length) {
    return (
      <StatePanel
        title={query ? "当前搜索没有匹配" : "这里还没有档案"}
        copy={query ? "档案没有消失；换一个短查询词再试试。" : "完成一次受控确认后，规范记忆才会出现在这里。"}
      />
    );
  }
  return (
    <div className="memory-list">
      {result.data.items.map((item) => (
        <button
          className={item.memoryId === selectedId ? "memory-row selected" : "memory-row"}
          type="button"
          key={item.memoryId}
          onClick={() => onSelect(item.memoryId)}
          aria-current={item.memoryId === selectedId ? "true" : undefined}
        >
          <div className="row-kicker">
            <StatusTag state={item.state} />
            <span>{memoryTypeLabels[item.memoryType] ?? "未知类型"} · {perspectiveLabel(item.perspective)}</span>
          </div>
          <h2>{item.title}</h2>
          <p>{item.summary}</p>
          <div className="row-footer"><span>第 {item.revisionNo} 版</span><time>{formatDate(item.updatedAt)}</time></div>
        </button>
      ))}
    </div>
  );
}

function MemoryDetailView({ memoryId, onBack }: { memoryId: string; onBack: () => void }) {
  const queryClient = useQueryClient();
  const detailQuery = useQuery({
    queryKey: [DETAIL_KEY, memoryId],
    queryFn: () => api.getMemory({ memoryId }),
  });

  useEffect(() => () => {
    queryClient.removeQueries({ queryKey: [EVIDENCE_KEY, memoryId] });
    queryClient.removeQueries({ queryKey: [DETAIL_KEY, memoryId] });
  }, [memoryId, queryClient]);

  if (detailQuery.isPending) return <LoadingState scope="详情" />;
  if (detailQuery.isError) return <ProblemPanel problem={classifyError(detailQuery.error)} />;
  return <DetailContent key={memoryId} memory={detailQuery.data.memory} onBack={onBack} />;
}

function DetailContent({ memory, onBack }: { memory: MemoryDetail; onBack: () => void }) {
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteSession, setDeleteSession] = useState(0);
  const [actionFeedback, setActionFeedback] = useState("");
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!evidenceOpen) queryClient.removeQueries({ queryKey: [EVIDENCE_KEY, memory.memoryId] });
  }, [evidenceOpen, memory.memoryId, queryClient]);
  const body = splitMemoryBody(memory.bodyText);
  const announceUnavailableAction = (action: string) => {
    setActionFeedback(`${action}当前本地 V1 尚未接线；未发出写请求。`);
  };
  const handleDeletionSucceeded = () => {
    void queryClient.invalidateQueries({ queryKey: [LIST_KEY] });
    onBack();
  };
  const openDeleteDrawer = (nextOpen: boolean) => {
    setDeleteOpen(nextOpen);
    setDeleteSession((session) => session + 1);
  };

  return (
    <>
      <button className="mobile-back" type="button" onClick={onBack}>← 返回档案</button>
      <header className="detail-header">
        <div className="detail-copy">
          <span className="eyebrow">记忆编号 {shortId(memory.memoryId)} · 第 {memory.revisionNo} 版</span>
          <h2>{body.title}</h2>
          {body.remainder && <p className="detail-body">{body.remainder}</p>}
        </div>
        <div className="detail-actions" role="group" aria-label="记忆治理动作">
          <button className="detail-action action-correct" type="button" onClick={() => announceUnavailableAction("与 hide 一起修正")}>与 hide 一起修正</button>
          <button className="detail-action" type="button" onClick={() => announceUnavailableAction("归档")}>归档</button>
          <button className="detail-action" type="button" onClick={() => announceUnavailableAction("隔离")}>隔离</button>
          <Dialog.Root open={deleteOpen} onOpenChange={openDeleteDrawer}>
            <Dialog.Trigger asChild>
              <button className="detail-action action-delete" type="button">永久删除</button>
            </Dialog.Trigger>
            <DeleteDrawer key={deleteSession} memory={memory} open={deleteOpen} onOpenChange={openDeleteDrawer} onDeleted={handleDeletionSucceeded} onBack={onBack} />
          </Dialog.Root>
          <p className="action-feedback" role="status" aria-live="polite">{actionFeedback}</p>
        </div>
      </header>
      <dl className="definition-strip">
        <div className="definition-item"><dt>规范状态</dt><dd><StatusTag state={memory.state} /></dd></div>
        <div className="definition-item"><dt>类型</dt><dd>{memoryTypeLabels[memory.memoryType] ?? "未知类型"}</dd></div>
        <div className="definition-item"><dt>视角</dt><dd>{shortId(memory.perspectiveActorId)}</dd></div>
        <div className="definition-item"><dt>证据</dt><dd>{memory.evidenceCount} 条消息边界</dd></div>
        <div className="definition-item"><dt>当前版本</dt><dd className="mono">第 {memory.revisionNo} 版</dd></div>
      </dl>
      <div className="detail-grid">
        <div>
          <section className="evidence-section">
            <div className="section-heading evidence-section-heading">
              <div>
                <h3>证据片段</h3>
                <p>最小必要边界，不是整段原文</p>
              </div>
              {memory.evidenceCount > 0 && (
                <Dialog.Root open={evidenceOpen} onOpenChange={setEvidenceOpen}>
                  <Dialog.Trigger asChild>
                    <button className="evidence-button" type="button">
                      查看完整证据 <span aria-hidden="true">{memory.evidenceCount}</span>
                    </button>
                  </Dialog.Trigger>
                  <EvidenceDrawer memory={memory} enabled={evidenceOpen} />
                </Dialog.Root>
              )}
            </div>
            {memory.evidenceCount > 0 ? (
              <div className="evidence-boundary">
                <span className="spine-dot" aria-hidden="true" />
                <strong>{memory.evidenceCount} 条消息边界已绑定</strong>
                <p>正文尚未发送到浏览器。点击后才按当前 revision 读取，关闭即从内存移除。</p>
              </div>
            ) : (
              <StatePanel title="当前没有可读原文" copy="这不代表记忆不存在；页面不会伪造证据片段。" compact />
            )}
          </section>
        </div>
        <aside className="governance-panel">
          <h3>治理边界</h3>
          <div className="governance-line"><span>访问策略</span><strong>尚未接入本地 V1</strong></div>
          <div className="governance-line"><span>普通检索</span><strong>尚未接入本地 V1</strong></div>
          <div className="governance-line"><span>派生阻断</span><strong>尚未接入本地 V1</strong></div>
          <div className="governance-line"><span>不确定性</span><strong>{memory.uncertaintyCode || "尚未接入本地 V1"}</strong></div>
          <div className="governance-note">
            <strong>治理写链当前不在本地 V1 前端开放</strong>
            <p>入口保留已验收位置与层级；点击只说明真实接线边界，不会伪造成功或发出写请求。</p>
          </div>
        </aside>
      </div>
    </>
  );
}

type DeletePhase = "loading" | "ready" | "confirming" | "polling" | "failed" | "timeout" | "error";

function DeleteDrawer({
  memory,
  open,
  onOpenChange,
  onDeleted,
  onBack,
  pollIntervalMs = 500,
  pollMaxAttempts = 10,
}: {
  memory: MemoryDetail;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted: () => void;
  onBack: () => void;
  pollIntervalMs?: number;
  pollMaxAttempts?: number;
}) {
  const [phase, setPhase] = useState<DeletePhase>("loading");
  const [preview, setPreview] = useState<DeletionPreviewResponse | null>(null);
  const [problem, setProblem] = useState<DeleteProblem | null>(null);
  const [runFailure, setRunFailure] = useState<{ requestId?: string; failureCode?: string; retryable?: boolean } | null>(null);
  const [runShortId, setRunShortId] = useState("");

  const pollTokenRef = useRef(0);
  const [keys] = useState(() => ({ previewKey: crypto.randomUUID(), confirmKey: crypto.randomUUID() }));

  const summary = useMemo(() => (preview ? summarizeClosure(preview.closureMembers) : null), [preview]);
  const evidenceSegments = useMemo(() => (preview ? groupByAnchor(preview.evidence) : []), [preview]);
  const impactSummary = useMemo(() => {
    if (!preview) return "";
    let retained = 0;
    let cleared = 0;
    let mixed = 0;
    for (const segment of evidenceSegments) {
      const retention = segmentRetention(segment.items);
      if (retention === "shared") retained += 1;
      else if (retention === "exclusive") cleared += 1;
      else mixed += 1;
    }
    const parts: string[] = [];
    if (retained > 0) parts.push(`${retained} 段保留`);
    if (cleared > 0) parts.push(`${cleared} 段清除`);
    if (mixed > 0) parts.push(`${mixed} 段部分保留`);
    return parts.length > 0 ? ` · 其中 ${parts.join("，")}` : "";
  }, [preview, evidenceSegments]);

  useEffect(() => {
    if (!open) return;
    const token = pollTokenRef.current;
    void (async () => {
      try {
        const requestManifestHash = await deletionRequestHash(memory.memoryId, memory.revisionNo, memory.currentPolicyRevisionNo);
        const result = await deletionApi.createDeletionPreview({
          idempotencyKey: keys.previewKey,
          governanceActionRequest: {
            targetId: memory.memoryId,
            expectedRevision: memory.revisionNo,
            expectedPolicyRevision: memory.currentPolicyRevisionNo,
            requestManifestHash,
          },
        });
        if (pollTokenRef.current !== token) return;
        setPreview(result);
        setPhase("ready");
      } catch (error) {
        if (pollTokenRef.current !== token) return;
        setPreview(null);
        setProblem(await classifyDeleteError(error));
        setPhase("error");
      }
    })();
  }, [open, keys, memory.memoryId, memory.revisionNo, memory.currentPolicyRevisionNo]);

  useEffect(() => () => {
    pollTokenRef.current += 1;
  }, []);

  const pollRun = async (runId: string, token: number): Promise<void> => {
    for (let attempt = 0; attempt < pollMaxAttempts; attempt += 1) {
      if (pollTokenRef.current !== token) return;
      try {
        const status = await deletionApi.getDeletionRun({ runId });
        if (pollTokenRef.current !== token) return;
        if (status.phase === "CANONICAL_COMMITTED" || status.phase === "INDEX_READY") {
          onDeleted();
          return;
        }
        if (status.phase === "FINAL_FAILED") {
          setPhase("failed");
          setRunFailure({ requestId: status.requestId, failureCode: status.failureCode, retryable: status.retryable });
          return;
        }
      } catch {
        // A failed poll is treated as an intermediate attempt; the next poll continues.
      }
      if (pollTokenRef.current !== token) return;
      if (attempt < pollMaxAttempts - 1) {
        await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
      }
    }
    if (pollTokenRef.current === token) {
      setPhase("timeout");
    }
  };

  const confirm = async (): Promise<void> => {
    if (!preview || phase === "confirming" || phase === "polling") return;
    const token = pollTokenRef.current;
    setPhase("confirming");
    setProblem(null);
    setRunFailure(null);
    try {
      const requestManifestHash = await deletionRequestHash(memory.memoryId, memory.revisionNo, memory.currentPolicyRevisionNo);
      const accepted = await deletionApi.confirmDeletion({
        idempotencyKey: keys.confirmKey,
        id: preview.previewId,
        deletionConfirmRequest: {
          targetId: memory.memoryId,
          expectedRevision: memory.revisionNo,
          expectedPolicyRevision: memory.currentPolicyRevisionNo,
          requestManifestHash,
          previewId: preview.previewId,
          previewRevision: preview.previewRevision,
          manifestHash: preview.manifestHash,
        },
      });
      if (pollTokenRef.current !== token) return;
      const resolved = resolveRun(accepted.statusUrl, accepted.runId);
      if (!resolved.ok) {
        setPhase("failed");
        setRunFailure({ requestId: undefined, failureCode: "DELETION_CLOSURE_MISMATCH", retryable: false });
        return;
      }
      setRunShortId(shortId(accepted.runId));
      await pollRun(accepted.runId, token);
    } catch (error) {
      if (pollTokenRef.current !== token) return;
      const classified = await classifyDeleteError(error);
      if (classified === "stale") {
        setPhase("error");
        setProblem("stale");
      } else {
        setProblem(classified);
        setPhase("ready");
      }
    }
  };

  const confirmDisabled = !summary?.valid || phase === "confirming" || phase === "polling";

  return (
    <Dialog.Portal>
      <Dialog.Overlay className="drawer-overlay" />
      <Dialog.Content className="delete-drawer" aria-describedby="delete-description">
        <div className="drawer-topline">
          <span className="eyebrow">永久删除 · 不可撤销</span>
          <Dialog.Close className="drawer-close" aria-label="关闭永久删除预览">×</Dialog.Close>
        </div>
        <Dialog.Title>永久删除影响预览</Dialog.Title>
        <Dialog.Description id="delete-description">
          系统已经按当前版本计算闭合范围。请只在这些影响与小林的意图一致时越过最终确认点。
        </Dialog.Description>

        {phase === "loading" && <LoadingState scope="删除预览" />}

        {phase === "error" && problem && (
          <div className="delete-problem" role="status">
            <h3>{DELETE_PROBLEM_COPY[problem][0]}</h3>
            <p>{DELETE_PROBLEM_COPY[problem][1]}</p>
          </div>
        )}

        {phase !== "loading" && phase !== "error" && summary?.valid && preview && (
          <div className="delete-impact-list">
            <div className="delete-evidence" aria-label="完整证据">
              <p className="delete-evidence-heading">完整证据：{evidenceSegments.length} 段，共 {preview.evidence.length} 条消息{impactSummary}</p>
              <EvidenceConversation items={preview.evidence} deletion={{ sharedMemories: preview.sharedMemories }} />
            </div>
          </div>
        )}

        {phase !== "loading" && phase !== "error" && summary && !summary.valid && (
          <div className="delete-impact-list">
            <div className="impact-row">
              <span>闭合范围</span>
              <strong>{summary.reason}</strong>
            </div>
          </div>
        )}

        {summary && !summary.valid && phase === "ready" && (
          <p className="delete-block-reason" role="status">{summary.reason}</p>
        )}

        <div className="impact-warning">确认后不可取消、扩大或重新打开。若清理失败，整个闭合范围继续保持隔离；当前房间里已经出现的文字可能仍然可见。</div>

        {phase === "ready" && problem && (
          <p className="delete-inline-problem" role="status">{DELETE_PROBLEM_COPY[problem][0]}：{DELETE_PROBLEM_COPY[problem][1]}</p>
        )}

        <div className="delete-actions">
          {phase === "failed" || phase === "timeout" ? (
            <button className="detail-action" type="button" onClick={onBack}>返回档案</button>
          ) : (
            <>
              <button className="delete-cancel" type="button" onClick={() => onOpenChange(false)}>取消</button>
              <button
                className="delete-confirm"
                type="button"
                disabled={confirmDisabled}
                onClick={() => void confirm()}
              >
                {phase === "confirming" || phase === "polling" ? "正在确认…" : "永久删除这 1 条记忆"}
              </button>
            </>
          )}
        </div>

        {phase === "failed" && runFailure && (
          <p className="delete-failure" role="status">
            永久删除执行失败 · {failureLabel(runFailure.failureCode)}
            {runFailure.requestId ? ` · 请求 ${shortId(runFailure.requestId)}` : ""} · {runFailure.retryable ? "可重试" : "不可重试"}
          </p>
        )}
        {phase === "timeout" && (
          <p className="delete-timeout" role="status">
            执行状态尚未收敛 · 记录 {runShortId}
          </p>
        )}

        <p className="drawer-footnote">操作绑定 {shortId(memory.memoryId)} · 第 {memory.revisionNo} 版。版本变化时失败关闭。</p>
      </Dialog.Content>
    </Dialog.Portal>
  );
}

function EvidenceDrawer({ memory, enabled }: { memory: MemoryDetail; enabled: boolean }) {
  const evidenceQuery = useQuery({
    queryKey: [EVIDENCE_KEY, memory.memoryId, memory.currentRevisionId],
    queryFn: () => api.getMemoryEvidence({ memoryId: memory.memoryId, revisionId: memory.currentRevisionId }),
    enabled,
    gcTime: 0,
  });

  return (
    <Dialog.Portal>
      <Dialog.Overlay className="drawer-overlay" />
      <Dialog.Content className="evidence-drawer" aria-describedby="evidence-description">
        <div className="drawer-topline">
          <span className="eyebrow">完整证据 · 只读</span>
          <Dialog.Close className="drawer-close" aria-label="关闭完整证据">×</Dialog.Close>
        </div>
        <Dialog.Title>这条记忆保存的完整证据</Dialog.Title>
        <Dialog.Description id="evidence-description">
          下面无截断展示本条记忆已经保存的全部最小必要证据；它不等于整场原对话。
        </Dialog.Description>
        <EvidenceResult result={evidenceQuery} memory={memory} />
        <p className="drawer-footnote">关闭后，这些证据正文会从页面查询内存中移除。</p>
      </Dialog.Content>
    </Dialog.Portal>
  );
}

type EvidenceQuery = ReturnType<typeof useQuery<MemoryEvidenceResponse>>;

function EvidenceResult({ result, memory }: { result: EvidenceQuery; memory: MemoryDetail }) {
  if (result.isPending) return <LoadingState scope="完整证据" />;
  if (result.isError) return <ProblemPanel problem={classifyError(result.error)} />;
  const items = [...result.data.evidenceItems].sort((a, b) => a.ordinal - b.ordinal);
  if (!items.length) return <StatePanel title="已保存证据当前不可用" copy="来源边界仍被诚实保留，页面不会用空字符串冒充原文。" compact />;

  const segments = groupByAnchor(items);
  const title = splitMemoryBody(memory.bodyText).title;
  const summary = `完整证据：${segments.length} 段，共 ${items.length} 条消息${formatEvidenceTimeRange(items) ? ` · ${formatEvidenceTimeRange(items)}` : ""}`;

  return (
    <>
      <div className="evidence-summary" aria-label="完整证据摘要">
        <p>对应记忆：<strong>{title}</strong></p>
        <p>{summary}</p>
      </div>
      <EvidenceConversation items={items} />
    </>
  );
}

interface EvidenceItemLike {
  anchorId: string;
  ordinal: number;
  displayLabel: string;
  occurredAt: Date;
  bodyText: string;
  sharedByMemoryIds?: string[];
}

interface DeletionImpact {
  sharedMemories: Array<{ memoryId: string; title: string }>;
}

function groupByAnchor<T extends EvidenceItemLike>(items: T[]) {
  const order: string[] = [];
  const byAnchor = new Map<string, T[]>();
  for (const item of items) {
    if (!byAnchor.has(item.anchorId)) {
      byAnchor.set(item.anchorId, []);
      order.push(item.anchorId);
    }
    byAnchor.get(item.anchorId)!.push(item);
  }
  return order.map((anchorId) => ({
    anchorId,
    items: byAnchor.get(anchorId)!.sort((a, b) => a.ordinal - b.ordinal),
  }));
}

function formatTime(value: Date) {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(value);
}

function formatEvidenceTimeRange(items: { occurredAt: Date }[]): string {
  if (!items.length) return "";
  const min = items.reduce((a, b) => (a.occurredAt.getTime() <= b.occurredAt.getTime() ? a : b));
  const max = items.reduce((a, b) => (a.occurredAt.getTime() >= b.occurredAt.getTime() ? a : b));
  const minLabel = formatDate(min.occurredAt);
  const maxLabel = formatDate(max.occurredAt);
  if (minLabel === maxLabel) return minLabel;
  const sameDay = min.occurredAt.getFullYear() === max.occurredAt.getFullYear()
    && min.occurredAt.getMonth() === max.occurredAt.getMonth()
    && min.occurredAt.getDate() === max.occurredAt.getDate();
  return sameDay ? `${minLabel}–${formatTime(max.occurredAt)}` : `${minLabel}–${maxLabel}`;
}

type SegmentRetention = "shared" | "exclusive" | "mixed";

function segmentMemoryIds(segment: EvidenceItemLike[]): string[] {
  return [...new Set(segment.flatMap((item) => item.sharedByMemoryIds ?? []))].sort();
}

function segmentRetention(segment: EvidenceItemLike[]): SegmentRetention {
  const retained = segment.filter((item) => (item.sharedByMemoryIds ?? []).length > 0).length;
  if (retained === 0) return "exclusive";
  if (retained === segment.length) return "shared";
  return "mixed";
}

function SegmentRetentionLabel({ segment, titleMap }: { segment: EvidenceItemLike[]; titleMap: Map<string, string> }) {
  const retention = segmentRetention(segment);
  const memoryIds = segmentMemoryIds(segment);
  const sharedTitles = memoryIds
    .map((id) => ({ id, title: titleMap.get(id) }))
    .filter((entry): entry is { id: string; title: string } => Boolean(entry.title));
  if (retention === "shared") {
    return (
      <div className="delete-segment-retention">
        <span className="delete-segment-retention-label">删除后会保留 · 另有 {memoryIds.length} 条记忆使用</span>
        {sharedTitles.length > 0 && (
          <ul className="delete-segment-titles">
            {sharedTitles.map(({ id, title }) => <li key={id}>《{title}》</li>)}
          </ul>
        )}
      </div>
    );
  }
  if (retention === "exclusive") {
    return (
      <div className="delete-segment-retention">
        <span className="delete-segment-retention-label">删除后会清除 · 仅当前记忆使用</span>
      </div>
    );
  }
  return (
    <div className="delete-segment-retention">
      <span className="delete-segment-retention-label">删除后部分保留</span>
    </div>
  );
}

function EvidenceConversation({ items, deletion }: { items: EvidenceItemLike[]; deletion?: DeletionImpact }) {
  const segments = groupByAnchor(items);
  const titleMap = deletion ? new Map(deletion.sharedMemories.map((s) => [s.memoryId, s.title])) : undefined;

  const renderMessages = (segment: { anchorId: string; items: EvidenceItemLike[] }, mixed: boolean) => (
    <div className="conversation">
      {segment.items.map((item) => {
        const retained = (item.sharedByMemoryIds ?? []).length > 0;
        const retention = mixed
          ? (retained
              ? `会保留${(item.sharedByMemoryIds ?? [])
                  .map((id) => titleMap?.get(id))
                  .filter((t): t is string => Boolean(t))
                  .map((t) => ` · 《${t}》`)
                  .join("")}`
              : "会清除")
          : undefined;
        return (
          <EvidenceBubble
            key={`${item.anchorId}-${item.ordinal}`}
            displayLabel={item.displayLabel}
            occurredAt={item.occurredAt}
            bodyText={item.bodyText}
            retention={retention}
            retained={retained}
          />
        );
      })}
    </div>
  );

  if (segments.length === 1) {
    const segment = segments[0];
    return (
      <div className={deletion ? "delete-segment" : undefined}>
        {deletion && <SegmentRetentionLabel segment={segment.items} titleMap={titleMap!} />}
        {renderMessages(segment, deletion ? segmentRetention(segment.items) === "mixed" : false)}
      </div>
    );
  }
  return (
    <>
      {segments.map((segment, index) => (
        <section className="evidence-segment" key={segment.anchorId} aria-label={`第 ${index + 1} 段证据`}>
          <h3 className="evidence-segment-heading">
            第 {index + 1} 段 · {segment.items.length} 条消息 · {formatEvidenceTimeRange(segment.items)}
          </h3>
          {deletion && <SegmentRetentionLabel segment={segment.items} titleMap={titleMap!} />}
          {renderMessages(segment, deletion ? segmentRetention(segment.items) === "mixed" : false)}
        </section>
      ))}
    </>
  );
}

function EvidenceBubble({ displayLabel, occurredAt, bodyText, retention, retained }: {
  displayLabel: string;
  occurredAt: Date;
  bodyText: string;
  retention?: string;
  retained?: boolean;
}) {
  const actor = actorPresentation(displayLabel);
  return (
    <article className={`evidence-message ${actor.side}`}>
      <div><strong>{actor.label}</strong><time>{formatDate(occurredAt)}</time></div>
      <p>{bodyText}</p>
      {retention && <span className={`delete-message-retention ${retained ? "retained" : "cleared"}`}>{retention}</span>}
    </article>
  );
}

function actorPresentation(displayLabel: string | undefined | null) {
  if (displayLabel === "小林") return { label: "小林", side: "xiaolin" };
  if (displayLabel === "hide") return { label: "hide", side: "hide" };
  return { label: "未知说话者", side: "neutral" };
}

function StatusTag({ state }: { state: string }) {
  return <span className={`status-tag ${state.toLocaleLowerCase()}`}>{state === "ACTIVE" ? "有效" : "归档"}</span>;
}

function ProblemPanel({ problem }: { problem: ReadProblem }) {
  const content: Record<ReadProblem, [string, string]> = {
    offline: ["档案没有消失，只是现在读不到", "本机连接或依赖暂不可用。页面不会把故障伪装成空档案。"],
    denied: ["当前没有查看权限", "系统不会透露目标是否存在，也不会显示标题、正文或来源片段。"],
    "not-found": ["没有找到可读取的档案", "目标不存在、版本不匹配或已经进入删除围栏时，都使用同一安全结果。"],
    invalid: ["这组读取条件无效", "请返回档案列表，使用支持的短查询和状态筛选。"],
    integrity: ["读取完整性检查没有通过", "依赖故障或载荷校验失败。正文不会以不完整状态继续展示。"],
  };
  return <StatePanel title={content[problem][0]} copy={content[problem][1]} problem={problem} />;
}

function LoadingState({ scope }: { scope: string }) {
  return (
    <div className="skeleton-stack" aria-label={`正在读取${scope}`} role="status">
      <span className="skeleton short" /><span className="skeleton" /><span className="skeleton block" />
    </div>
  );
}

function StatePanel({ title, copy, compact = false, problem }: { title: string; copy: string; compact?: boolean; problem?: string }) {
  return (
    <div className={compact ? "state-panel compact" : "state-panel"} data-problem={problem}>
      <span className="state-mark" aria-hidden="true" />
      <h2>{title}</h2>
      <p>{copy}</p>
    </div>
  );
}

function perspectiveLabel(value: string) {
  if (value.toLocaleLowerCase().startsWith("hide:")) return "hide";
  if (value.toLocaleLowerCase().startsWith("xiaolin:")) return "小林";
  return "共同视角";
}

function splitMemoryBody(value: string) {
  const lines = value.replace(/\r\n?/g, "\n").split("\n");
  const titleIndex = lines.findIndex((line) => line.trim().length > 0);
  if (titleIndex < 0) return { title: "未命名记忆", remainder: "" };
  return {
    title: lines[titleIndex].trim(),
    remainder: lines.slice(titleIndex + 1).join("\n").trim(),
  };
}

function shortId(value: string) {
  return value.length > 12 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

function formatDate(value: Date) {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(value);
}

export function App({ client }: { client?: QueryClient }) {
  const ownedClient = useMemo(() => client ?? queryClientFactory(), [client]);
  return (
    <QueryClientProvider client={ownedClient}>
      <BrowserRouter>
        <AppShell />
      </BrowserRouter>
    </QueryClientProvider>
  );
}
