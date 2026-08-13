import * as Dialog from "@radix-ui/react-dialog";
import {
  Configuration,
  FetchError,
  MemoriesApi,
  MemoryState,
  ResponseError,
  type MemoryDetail,
  type MemoryEvidenceItem,
  type MemoryEvidenceResponse,
  type MemoryListItem,
} from "@hide-nest/api-client-ts";
import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
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
import { EVIDENCE_KEY, queryClientFactory } from "./query";

const api = new MemoriesApi(new Configuration({ basePath: "/v1" }));
const LIST_KEY = "local-v1-memories";

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
    queryKey: ["local-v1-memory", memoryId],
    queryFn: () => api.getMemory({ memoryId }),
  });

  useEffect(() => () => {
    queryClient.removeQueries({ queryKey: [EVIDENCE_KEY, memoryId] });
  }, [memoryId, queryClient]);

  if (detailQuery.isPending) return <LoadingState scope="详情" />;
  if (detailQuery.isError) return <ProblemPanel problem={classifyError(detailQuery.error)} />;
  return <DetailContent key={memoryId} memory={detailQuery.data.memory} onBack={onBack} />;
}

function DetailContent({ memory, onBack }: { memory: MemoryDetail; onBack: () => void }) {
  const [evidenceOpen, setEvidenceOpen] = useState(false);
  const [actionFeedback, setActionFeedback] = useState("");
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!evidenceOpen) queryClient.removeQueries({ queryKey: [EVIDENCE_KEY, memory.memoryId] });
  }, [evidenceOpen, memory.memoryId, queryClient]);
  const body = splitMemoryBody(memory.bodyText);
  const announceUnavailableAction = (action: string) => {
    setActionFeedback(`${action}当前本地 V1 尚未接线；未发出写请求。`);
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
          <button className="detail-action action-delete" type="button" onClick={() => announceUnavailableAction("永久删除")}>永久删除</button>
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
        <EvidenceResult result={evidenceQuery} />
        <p className="drawer-footnote">关闭后，这些证据正文会从页面查询内存中移除。</p>
      </Dialog.Content>
    </Dialog.Portal>
  );
}

type EvidenceQuery = ReturnType<typeof useQuery<MemoryEvidenceResponse>>;

function EvidenceResult({ result }: { result: EvidenceQuery }) {
  if (result.isPending) return <LoadingState scope="完整证据" />;
  if (result.isError) return <ProblemPanel problem={classifyError(result.error)} />;
  const items = [...result.data.evidenceItems].sort((a, b) => a.ordinal - b.ordinal);
  if (!items.length) return <StatePanel title="已保存证据当前不可用" copy="来源边界仍被诚实保留，页面不会用空字符串冒充原文。" compact />;
  if (items.length === 1) return <SingleEvidence item={items[0]} />;
  return (
    <div className="conversation" aria-label="多人证据记录">
      {items.map((item) => <EvidenceMessage item={item} key={`${item.anchorId}-${item.ordinal}`} />)}
    </div>
  );
}

function SingleEvidence({ item }: { item: MemoryEvidenceItem }) {
  return (
    <article className="single-evidence">
      <EvidenceMeta item={item} />
      <blockquote>{item.bodyText}</blockquote>
    </article>
  );
}

function EvidenceMessage({ item }: { item: MemoryEvidenceItem }) {
  const actor = actorPresentation(item);
  return (
    <article className={`evidence-message ${actor.side}`}>
      <div><strong>{actor.label}</strong><time>{formatDate(item.occurredAt)}</time></div>
      <p>{item.bodyText}</p>
    </article>
  );
}

function EvidenceMeta({ item }: { item: MemoryEvidenceItem }) {
  const actor = actorPresentation(item);
  return (
    <dl className="evidence-meta">
      <div><dt>说话者</dt><dd>{actor.label}</dd></div>
      <div><dt>时间</dt><dd>{formatDate(item.occurredAt)}</dd></div>
      <div><dt>消息边界</dt><dd>第 {item.ordinal} 条已保存消息</dd></div>
    </dl>
  );
}

function actorPresentation(item: MemoryEvidenceItem) {
  const stable = item.actorStableRef.toLocaleLowerCase();
  if (stable.startsWith("hide:")) return { label: "hide", side: "hide" };
  if (stable.startsWith("xiaolin:")) return { label: "小林", side: "xiaolin" };
  return { label: "未标注参与者", side: "neutral" };
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
