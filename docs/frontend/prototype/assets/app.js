"use strict";

const memories = [
  {
    id: "mem-7f12",
    revision: 3,
    state: "ACTIVE",
    isolated: false,
    type: "Interpretation",
    typeZh: "理解",
    perspective: "共同",
    title: "对协作者乙的看法，从误解慢慢变成理解",
    summary: "一开始觉得彼此很难配合；后来一起完成了纸船计划，发现对方只是表达方式直接，关系已经缓和。",
    updated: "今天 08:42",
    source: "完整",
    access: "available",
    uncertainty: "已确认",
    policyRevision: 7,
    delivered: true,
    evidence: [
      {
        actor: "小林（合成）",
        time: "07 月 18 日 · 21:13",
        source: "原型工作房间（合成）",
        boundary: "连续 2 条消息",
        text: "我和协作者乙总是说不到一起去。今天讨论纸船计划时，他又直接否定了我最开始的做法，我当时很不舒服，暂时不想再合作。",
        messages: [
          { speaker: "hide", side: "left", time: "21:12", text: "你刚刚说到协作者乙直接否定了最开始的做法。那一刻你最在意的是什么？" },
          { speaker: "小林", side: "right", time: "21:13", text: "我和协作者乙总是说不到一起去。今天讨论纸船计划时，他又直接否定了我最开始的做法，我当时很不舒服，暂时不想再合作。" }
        ],
        note: "旧状态 · 对话中的必要片段"
      },
      {
        actor: "小林（合成）",
        time: "08 月 02 日 · 18:26",
        source: "原型工作房间（合成）",
        boundary: "连续 3 条消息",
        text: "纸船计划后来意外做得很顺。下班后我们又一起散步聊了很久，我才知道他不是在否定我，只是表达方式比较直接。我好像误解了他，现在觉得关系缓和了不少。",
        messages: [
          { speaker: "小林", side: "right", time: "18:24", text: "纸船计划后来意外做得很顺。下班后我们又一起散步聊了很久。" },
          { speaker: "hide", side: "left", time: "18:25", text: "所以先前那种“很难配合”的判断，现在已经发生变化了吗？" },
          { speaker: "小林", side: "right", time: "18:26", text: "嗯。我才知道他不是在否定我，只是表达方式比较直接。我好像误解了他，现在觉得关系缓和了不少。" }
        ],
        note: "后来变化 · 对话中的必要片段"
      }
    ],
    relations: [
      { kind: "SUPERSEDES", copy: "修订 3 限定了修订 1 的旧判断；旧事实仍保留来路，不参与当前回答。" },
      { kind: "PART_OF_TRAJECTORY", copy: "属于“协作关系变化”轨迹，当前状态不是“关系非常好”。" }
    ]
  },
  {
    id: "mem-b810",
    revision: 2,
    state: "ACTIVE",
    isolated: false,
    type: "Principle",
    typeZh: "原则",
    perspective: "小林",
    title: "重要决定要写成边界清楚、可以交接的工单",
    summary: "遇到复杂工程时，先区分小林能做的部分与辅助模型能做的部分，再为关键风险保留复核门。",
    updated: "昨天 19:20",
    source: "完整",
    access: "available",
    uncertainty: "已确认",
    policyRevision: 4,
    delivered: false,
    evidence: [
      { actor: "小林（合成）", time: "08 月 01 日 · 10:04", text: "我不想让指挥官钻进繁杂代码，把任务拆成我能做的和辅助模型能做的吧。", note: "决策来源 · 对话中的必要片段" }
    ],
    relations: [{ kind: "REFINES", copy: "把“尽量节约注意力”修订为可执行的任务分权与复核规则。" }]
  },
  {
    id: "mem-44ad",
    revision: 1,
    state: "ACTIVE",
    isolated: false,
    type: "Claim",
    typeZh: "偏好",
    perspective: "小林",
    title: "创作空间偏爱暖灰、留白和少量粉色生命力",
    summary: "喜欢安静、温暖、略带艺术感的界面；不喜欢黑底荧光、毛茸茸卡通窝或普通运维后台。",
    updated: "昨天 17:46",
    source: "部分",
    access: "partial",
    uncertainty: "明确",
    policyRevision: 2,
    delivered: false,
    evidence: [
      { actor: "小林（合成）", time: "08 月 05 日 · 17:46", text: "主体克制清楚，偶尔藏一点不规整的小细节和偏粉的生命力。", note: "最小充分证据 · 其余闲聊未保留" }
    ],
    relations: []
  },
  {
    id: "mem-93cc",
    revision: 1,
    state: "ACTIVE",
    isolated: false,
    type: "Event",
    typeZh: "事件",
    perspective: "小林",
    title: "小岚领养了一只叫“蒲公英”的橘猫",
    summary: "这是完全虚构的原型内容，用来展示第三方与宠物信息的最小背景和来源边界。",
    updated: "07 月 30 日",
    source: "不可用",
    access: "unavailable",
    uncertainty: "待核对",
    policyRevision: 3,
    delivered: false,
    evidence: [],
    relations: [{ kind: "EVIDENCED_BY", copy: "来源坐标仍在，但原文载荷已按保留策略清理。" }]
  },
  {
    id: "mem-1d30",
    revision: 4,
    state: "ARCHIVED",
    isolated: false,
    type: "Claim",
    typeZh: "习惯",
    perspective: "小林",
    title: "曾经习惯在深夜集中整理创作灵感",
    summary: "这条习惯已经归档，不再进入普通检索；恢复为有效状态也不会自动扩大原有访问权限。",
    updated: "07 月 12 日",
    source: "完整",
    access: "available",
    uncertainty: "历史",
    policyRevision: 6,
    delivered: false,
    evidence: [{ actor: "小林（合成）", time: "06 月 28 日 · 00:41", text: "以前常常等到夜深才整理灵感，现在想把作息慢慢提前。", note: "变化前状态 · 对话中的必要片段" }],
    relations: [{ kind: "SUPERSEDED_BY", copy: "被新的作息偏好限定；保留为历史轨迹。" }]
  },
  {
    id: "mem-e521",
    revision: 2,
    state: "ACTIVE",
    isolated: true,
    type: "Calibration",
    typeZh: "校准",
    perspective: "共同",
    title: "一条被隔离的私人边界校准样例",
    summary: "当前访问策略已收紧，普通检索和上下文包都不能取得；本页仅以授权治理视图展示合成摘要。",
    updated: "07 月 08 日",
    source: "未展示",
    access: "denied",
    uncertainty: "已确认",
    policyRevision: 9,
    delivered: true,
    evidence: [],
    relations: []
  }
];

const reviews = [
  { id: "review-2a71", title: "关于新工作节奏的 3 条候选", members: 3, opened: "今天 08:37", state: "OPEN", source: "当前对话" },
  { id: "review-81c4", title: "一次创作偏好修订", members: 1, opened: "昨天 22:10", state: "OPEN", source: "已暂缓" },
  { id: "review-29d0", title: "纸船计划阶段总结", members: 4, opened: "08 月 02 日", state: "COMPLETED", source: "已完成" }
];

const runs = [
  { id: "run-9821", kind: "CLOSEOUT", title: "3 条记忆保存", phase: "INDEX_READY", time: "今天 08:42", status: "ready", detail: "已安全接收、已保存，检索已经追上当前版本。" },
  { id: "run-771a", kind: "DELETION", title: "永久删除 1 条记忆", phase: "FINAL_FAILED", time: "昨天 23:18", status: "failed", detail: "在线对象仍保持隔离。失败原因：原文存储暂不可用。" },
  { id: "run-a033", kind: "INDEX", title: "索引同步 4 条版本", phase: "CANONICAL_COMMITTED", time: "昨天 17:48", status: "pending", detail: "规范记忆已经保存，向量检索尚未就绪。" },
  { id: "run-1c85", kind: "EXPORT", title: "开放格式导出", phase: "RECEIVED", time: "07 月 31 日", status: "pending", detail: "请求已安全接收，尚未形成可下载制品。" }
];

const routeMeta = {
  memories: ["记忆档案", "留下来的，不必喧哗"],
  reviews: ["待续确认", "没有完成的，也有清楚的位置"],
  runs: ["处理记录", "每一步，抵达哪里就说到哪里"],
  status: ["系统状态", "让可靠保持可见，但不喧宾夺主"]
};

const state = {
  route: "memories",
  selectedId: memories[0].id,
  filter: "all",
  query: "",
  scenario: "normal",
  drawerAction: null,
  lastFocus: null,
  sortNewest: true
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

function selectedMemory() {
  return memories.find(memory => memory.id === state.selectedId) || memories[0];
}

function evidencePreview(text, limit = 52) {
  const characters = Array.from(String(text));
  return characters.length > limit ? `${characters.slice(0, limit).join("")}……` : characters.join("");
}

function evidenceParticipants(entry) {
  if (!entry.messages?.length) return entry.actor;
  return [...new Set(entry.messages.map(message => message.speaker))].join("、");
}

function renderFullEvidenceContent(entry, evidenceIndex) {
  if (!entry.messages || entry.messages.length <= 1) {
    return `<blockquote class="full-evidence-body">「${escapeHtml(entry.text)}」</blockquote>`;
  }
  return `<div class="evidence-conversation" aria-label="证据 ${evidenceIndex + 1} 的多人对话记录">
    ${entry.messages.map(message => {
      const sideClass = message.side === "right" ? "is-xiaolin" : "is-hide";
      return `<div class="evidence-message ${sideClass}">
        <div class="evidence-message-meta"><strong>${escapeHtml(message.speaker)}</strong><span>${escapeHtml(message.time)}</span></div>
        <p>${escapeHtml(message.text)}</p>
      </div>`;
    }).join("")}
  </div>`;
}

function statusTag(memory) {
  if (memory.isolated) return '<span class="status-tag isolated">隔离</span>';
  if (memory.state === "ARCHIVED") return '<span class="status-tag archived">归档</span>';
  return '<span class="status-tag active">有效</span>';
}

function accessLabel(memory) {
  return ({ available: "证据片段可用", partial: "部分片段可用", unavailable: "原文已清理", denied: "证据未展示" })[memory.access] || "未知";
}

function relationLabel(kind) {
  return ({
    SUPERSEDES: "替代旧判断",
    PART_OF_TRAJECTORY: "属于变化轨迹",
    REFINES: "细化旧表述",
    EVIDENCED_BY: "由来源证明",
    SUPERSEDED_BY: "已被新版本限定"
  })[kind] || "关联关系";
}

function phaseLabel(phase) {
  return ({
    RECEIVED: "已安全接收",
    DECISIONS_COMMITTED: "决定已提交",
    CANONICAL_COMMITTED: "规范记忆已保存",
    INDEX_READY: "检索已就绪",
    FINAL_FAILED: "最终处理失败"
  })[phase] || "处理中";
}

function filteredMemories() {
  const query = state.query.trim().toLowerCase();
  const result = memories.filter(memory => {
    const matchesFilter = state.filter === "all" ||
      (state.filter === "ISOLATED" ? memory.isolated : memory.state === state.filter);
    const haystack = `${memory.title} ${memory.summary} ${memory.typeZh} ${memory.perspective}`.toLowerCase();
    return matchesFilter && (!query || haystack.includes(query));
  });
  return state.sortNewest ? result : [...result].reverse();
}

function renderMemoryList() {
  const list = $("#memoryList");
  const count = $("#resultCount");
  if (state.scenario === "loading") {
    list.innerHTML = '<div class="skeleton-stack" aria-label="正在加载"><div class="skeleton skeleton-line short"></div><div class="skeleton skeleton-block"></div><div class="skeleton skeleton-block"></div><div class="skeleton skeleton-block"></div></div>';
    count.textContent = "读取中";
    return;
  }
  if (["empty", "denied", "failed"].includes(state.scenario)) {
    const content = statePanelContent(state.scenario, "list");
    list.innerHTML = `<div class="state-panel"><div><div class="state-mark"></div><h2>${content.title}</h2><p>${content.copy}</p></div></div>`;
    count.textContent = state.scenario === "empty" ? "0 条档案" : "状态未知";
    return;
  }
  const items = filteredMemories();
  count.textContent = `${items.length} 条档案`;
  $("#memoryNavCount").textContent = memories.length;
  if (!items.length) {
    list.innerHTML = '<div class="state-panel"><div><div class="state-mark"></div><h2>当前筛选没有匹配</h2><p>档案仍在，只是没有符合这组条件。清除搜索或切换筛选即可继续。</p></div></div>';
    return;
  }
  list.innerHTML = items.map(memory => `
    <button class="memory-row ${memory.id === state.selectedId ? "is-selected" : ""}" type="button" data-memory-id="${memory.id}" aria-current="${memory.id === state.selectedId ? "true" : "false"}">
      <div class="row-kicker">${statusTag(memory)}<span>${escapeHtml(memory.typeZh)} · ${escapeHtml(memory.perspective)}</span></div>
      <h3 class="row-title">${escapeHtml(memory.title)}</h3>
      <p class="row-summary">${escapeHtml(memory.summary)}</p>
      <div class="row-footer"><span>第 ${memory.revision} 版</span><span>${escapeHtml(memory.updated)}</span></div>
    </button>`).join("");
}

function renderMemoryDetail() {
  const detail = $("#memoryDetail");
  if (state.scenario === "loading") {
    detail.innerHTML = '<div class="skeleton-stack"><div class="skeleton skeleton-line short"></div><div class="skeleton skeleton-line medium"></div><div class="skeleton skeleton-line"></div><div class="skeleton skeleton-block"></div><div class="skeleton skeleton-block"></div></div>';
    return;
  }
  if (["empty", "denied", "failed"].includes(state.scenario)) {
    const content = statePanelContent(state.scenario, "detail");
    detail.innerHTML = `<div class="state-panel"><div><div class="state-mark"></div><h2>${content.title}</h2><p>${content.copy}</p>${state.scenario === "failed" ? '<button class="button" type="button" data-reset-scenario>重新连接</button>' : ""}</div></div>`;
    return;
  }
  const memory = selectedMemory();
  if (!memory) return;
  const stale = state.scenario === "stale";
  const evidenceHtml = memory.evidence.length
    ? memory.evidence.map(entry => `<div class="evidence-entry"><div class="evidence-meta"><span>${escapeHtml(entry.actor)}</span><span>${escapeHtml(entry.time)}</span></div><blockquote>「${escapeHtml(evidencePreview(entry.text))}」</blockquote><p class="evidence-note">${escapeHtml(entry.note)} · 此处为阅读预览</p></div>`).join("")
    : `<div class="state-panel" style="min-height:210px;padding:28px 0"><div><div class="state-mark"></div><h2 style="font-size:21px">${memory.access === "unavailable" ? "来源坐标仍在，原文已经清理" : "证据正文当前不展示"}</h2><p>${memory.access === "unavailable" ? "这不是空证据；系统保留了来源不可用的事实，但不会伪造原文。" : "隔离策略正在生效。无权内容不会以标题、片段或计数泄露。"}</p></div></div>`;
  const relationsHtml = memory.relations.length
    ? memory.relations.map(relation => `<div class="relation-row"><span class="relation-kind">${escapeHtml(relationLabel(relation.kind))}</span><span class="relation-copy">${escapeHtml(relation.copy)}</span></div>`).join("")
    : '<p class="evidence-note" style="padding-top:14px">当前版本没有额外关系。未知会保持未知，不为了页面完整而补写。</p>';
  detail.innerHTML = `
    <div class="detail-header">
      <div>
        <button class="text-button mobile-detail-back" type="button" data-mobile-back hidden>← 返回档案</button>
        <span class="eyebrow">记忆编号 ${escapeHtml(memory.id)} · 第 ${memory.revision} 版</span>
        <h2 class="detail-title">${escapeHtml(memory.title)}</h2>
        <p class="detail-lede">${escapeHtml(memory.summary)}</p>
      </div>
      <div class="detail-actions" aria-label="记忆治理动作">
        <button class="button action-correct" type="button" data-action="correct" ${stale ? "disabled" : ""}>与 hide 一起修正</button>
        <button class="button" type="button" data-action="${memory.state === "ARCHIVED" ? "restore-active" : "archive"}" ${stale ? "disabled" : ""}>${memory.state === "ARCHIVED" ? "恢复有效" : "归档"}</button>
        <button class="button" type="button" data-action="${memory.isolated ? "restore-isolation" : "isolate"}" ${stale ? "disabled" : ""}>${memory.isolated ? "恢复隔离" : "隔离"}</button>
        <button class="button action-delete" type="button" data-action="delete" ${stale ? "disabled" : ""}>永久删除</button>
      </div>
    </div>
    ${stale ? '<div class="impact-warning" role="alert"><strong>页面中的版本已经过期。</strong><br>旧选择不会应用到新版本。请读取当前版本后再操作。 <button class="text-button" type="button" data-reset-scenario>读取当前版本</button></div>' : ""}
    <dl class="definition-strip">
      <div class="definition-item"><dt>规范状态</dt><dd>${statusTag(memory)}</dd></div>
      <div class="definition-item"><dt>类型</dt><dd>${escapeHtml(memory.typeZh)}</dd></div>
      <div class="definition-item"><dt>视角</dt><dd>${escapeHtml(memory.perspective)}</dd></div>
      <div class="definition-item"><dt>证据</dt><dd>${escapeHtml(accessLabel(memory))}</dd></div>
      <div class="definition-item"><dt>当前版本</dt><dd class="mono">第 ${memory.revision} 版</dd></div>
    </dl>
    <div class="detail-grid">
      <div>
        <section class="detail-section">
          <div class="section-heading evidence-section-heading">
            <div><h2>证据片段</h2><span>最小必要片段，不是整段原文</span></div>
            ${memory.evidence.length ? `<button class="text-button evidence-full-button" type="button" data-view-evidence>查看完整证据 <span aria-hidden="true">${memory.evidence.length}</span></button>` : ""}
          </div>
          <div class="evidence-thread">${evidenceHtml}</div>
        </section>
        <section class="detail-section">
          <div class="section-heading"><h2>变化轨迹</h2><span>当前关系视图</span></div>
          <div class="relation-list">${relationsHtml}</div>
        </section>
      </div>
      <aside class="governance-panel">
        <h3>治理边界</h3>
        <div class="governance-line"><span>访问策略</span><strong>第 ${memory.policyRevision} 版</strong></div>
        <div class="governance-line"><span>普通检索</span><strong>${memory.isolated || memory.state === "ARCHIVED" ? "不参与" : "允许"}</strong></div>
        <div class="governance-line"><span>派生阻断</span><strong>${memory.isolated ? "已建立" : "无"}</strong></div>
        <div class="governance-line"><span>不确定性</span><strong>${escapeHtml(memory.uncertainty)}</strong></div>
        <div class="honesty-note"><strong>${memory.delivered ? "当前房间可能仍含旧内容" : "当前未发现已投递记录"}</strong>${memory.delivered ? "后续撤权会阻止新的投递，但系统不会谎称已经擦除当前对话窗口里的文字。" : "这只说明当前投递记录，不代表任意历史窗口都能被远程擦除。"}</div>
      </aside>
    </div>`;
  if (window.matchMedia("(max-width: 820px)").matches) {
    const back = $("[data-mobile-back]", detail);
    if (back) back.hidden = false;
  }
}

function statePanelContent(scenario, scope) {
  const content = {
    empty: { title: "这里还没有档案", copy: scope === "list" ? "完成一次对话内确认后，规范记忆才会出现在这里。" : "先从左侧选择一条档案。" },
    denied: { title: "当前没有查看权限", copy: "系统不会透露目标是否存在，也不会显示标题、正文或来源片段。" },
    failed: { title: "档案没有消失，只是现在读不到", copy: "远端核心连接失败。页面不会把故障伪装成“没有记忆”，写操作已经暂停。" }
  };
  return content[scenario] || content.empty;
}

function renderReviews() {
  const view = $("#reviewsView");
  if (state.scenario !== "normal") return renderCollectionScenario(view);
  view.innerHTML = `
    <div>
      <div class="collection-intro"><h2>待续，不等于已经确认</h2><p>这里展示冻结的确认单状态。真正的增删改和最终确认仍回到当前对话，页面不会替小林做第二套决定。</p></div>
      <div class="collection-list">${reviews.map(review => `
        <article class="collection-row">
          <div class="collection-id">确认单 ${escapeHtml(review.id.slice(-4))}</div>
          <div><h3 class="collection-title">${escapeHtml(review.title)}</h3><p class="collection-copy">${review.members} 个成员 · ${escapeHtml(review.source)}</p></div>
          <div class="collection-time">${escapeHtml(review.opened)}</div>
          <div class="collection-actions">${review.state === "OPEN" ? `<button class="button" type="button" data-review-return="${review.id}">返回对话</button><button class="button ghost" type="button" data-review-cancel="${review.id}">取消</button>` : '<span class="status-tag ready">已完成</span>'}</div>
        </article>`).join("")}</div>
    </div>`;
}

function runPhaseIndex(phase) {
  return ({ RECEIVED: 0, DECISIONS_COMMITTED: 1, CANONICAL_COMMITTED: 2, INDEX_READY: 3, FINAL_FAILED: 2 })[phase] ?? 0;
}

function renderRuns() {
  const view = $("#runsView");
  if (state.scenario !== "normal") return renderCollectionScenario(view);
  const featured = runs[0];
  const steps = ["已安全接收", "决定已提交", "记忆已保存", "检索已就绪"];
  const activeIndex = runPhaseIndex(featured.phase);
  view.innerHTML = `
    <div>
      <div class="collection-intro"><h2>保存与检索，是不同的抵达</h2><p>系统只报告已经发生的事实。规范对象保存成功后，即使索引暂时失败，也不会倒退成“记忆未保存”。</p></div>
      <div class="run-timeline" aria-label="最近一次运行阶段">${steps.map((step, index) => `<div class="run-step ${index < activeIndex ? "is-done" : index === activeIndex ? "is-current" : ""}"><strong>${step}</strong><span>${index <= activeIndex ? "已完成" : "等待中"}</span></div>`).join("")}</div>
      <div class="collection-list">${runs.map(run => `
        <article class="collection-row">
          <div class="collection-id">记录 ${escapeHtml(run.id.slice(-4))}</div>
          <div><h3 class="collection-title">${escapeHtml(run.title)}</h3><p class="collection-copy">${escapeHtml(run.detail)}</p></div>
          <div class="collection-time">${escapeHtml(run.time)}</div>
          <div class="collection-actions"><span class="status-tag ${run.status}">${escapeHtml(phaseLabel(run.phase))}</span></div>
        </article>`).join("")}</div>
    </div>`;
}

function renderStatus() {
  const view = $("#statusView");
  if (state.scenario !== "normal") return renderCollectionScenario(view);
  const cells = [
    ["本地接入器", "HEALTHY", "正常", "仅在本机运行；浏览器未持有远端令牌。", "刚刚"],
    ["远端核心", "HEALTHY", "正常", "加密连接可达，规范写入与查询正常。", "刚刚"],
    ["全文检索", "HEALTHY", "正常", "当前版本已经同步。", "12 秒前"],
    ["向量检索", "DEGRADED", "正在追赶", "仍可安全使用全文检索；向量路线正在追赶 1 条版本。", "38 秒前"],
    ["证据存储", "HEALTHY", "正常", "只显示可访问性，不暴露对象位置。", "1 分钟前"],
    ["真实材料入口", "DISABLED", "未启用", "生产接收真实材料仍关闭。", "配置基线" ]
  ];
  view.innerHTML = `
    <div>
      <div class="collection-intro"><h2>只展示能帮助行动的状态</h2><p>这里不出现令牌、数据库连接、存储桶、内部地址或供应商错误原文。故障路线与仍可安全使用的范围分别说明。</p></div>
      <div class="status-grid">${cells.map(([name, status, statusCopy, copy, time]) => `<article class="status-cell"><div><h3>${name}</h3><p>${copy}</p><code>${time}</code></div><span class="status-tag ${status === "HEALTHY" ? "ready" : status === "DEGRADED" ? "partial" : "archived"}">${statusCopy}</span></article>`).join("")}</div>
    </div>`;
}

function renderCollectionScenario(view) {
  if (state.scenario === "loading") {
    view.innerHTML = '<div class="skeleton-stack" style="max-width:1000px"><div class="skeleton skeleton-line short"></div><div class="skeleton skeleton-line medium"></div><div class="skeleton skeleton-block"></div><div class="skeleton skeleton-block"></div></div>';
    return;
  }
  if (state.scenario === "stale") {
    view.innerHTML = '<div class="state-panel"><div><div class="state-mark"></div><h2>这个页面的版本已经变化</h2><p>旧结果不会继续执行。重新读取当前数据后再做决定。</p><button class="button" type="button" data-reset-scenario>读取当前版本</button></div></div>';
    return;
  }
  const content = statePanelContent(state.scenario === "normal" ? "empty" : state.scenario, "collection");
  view.innerHTML = `<div class="state-panel"><div><div class="state-mark"></div><h2>${content.title}</h2><p>${content.copy}</p></div></div>`;
}

function renderRoute() {
  const [eyebrow, title] = routeMeta[state.route];
  $("#pageEyebrow").textContent = eyebrow;
  $("#pageTitle").textContent = title;
  $$(".nav-item").forEach(item => item.classList.toggle("is-active", item.dataset.route === state.route));
  $$(".route-view").forEach(view => view.classList.remove("is-active"));
  $(`#${state.route}View`).classList.add("is-active");
  if (state.route === "memories") {
    renderMemoryList();
    renderMemoryDetail();
  } else if (state.route === "reviews") renderReviews();
  else if (state.route === "runs") renderRuns();
  else renderStatus();
  applyGlobalScenario();
}

function applyGlobalScenario() {
  const notice = $("#globalNotice");
  const connection = $(".connection-heading span:last-child");
  if (state.scenario === "failed") {
    notice.textContent = "远端核心暂时不可达。档案没有被判定为空，所有治理写操作已暂停。";
    notice.classList.remove("is-hidden");
    if (connection) connection.textContent = "连接暂不可用";
  } else {
    notice.classList.add("is-hidden");
    if (connection) connection.textContent = "本机连接正常";
  }
}

function navigate(route) {
  state.route = route;
  state.scenario = $("#scenarioSelect").value;
  window.location.hash = route;
  $("#sideRail").classList.remove("is-open");
  $("#mobileMenuButton").setAttribute("aria-expanded", "false");
  renderRoute();
  $("#pageTitle").focus?.();
}

function openDrawer(action) {
  const memory = selectedMemory();
  if (!memory || state.scenario !== "normal") return;
  state.drawerAction = action;
  state.lastFocus = document.activeElement;
  const drawer = $("#actionDrawer");
  const scrim = $("#scrim");
  const content = $("#drawerContent");
  drawer.classList.remove("is-evidence");
  const configs = {
    archive: {
      eyebrow: "归档 · 可逆",
      title: "把这条记忆归档",
      description: "归档后它不再参与普通检索，但规范来路仍保留，也可以恢复为有效。归档不等于删除原文。",
      rows: [["目标", memory.title], ["绑定版本", `第 ${memory.revision} 版`], ["普通检索", "停止参与"], ["可恢复", "是"]],
      confirm: "归档",
      tone: "primary"
    },
    "restore-active": {
      eyebrow: "恢复 · 可逆",
      title: "恢复为有效记忆",
      description: "恢复为有效只改变逻辑状态，不自动解除隔离，也不会扩大当前访问权限。",
      rows: [["目标", memory.title], ["当前状态", "已归档"], ["恢复后", "有效"], ["访问策略", `仍使用第 ${memory.policyRevision} 版策略`]],
      confirm: "恢复有效",
      tone: "primary"
    },
    isolate: {
      eyebrow: "隔离 · 收紧访问",
      title: "隔离这条记忆",
      description: "系统会先收紧访问策略，并为当前谱系建立派生阻断。它不会自动删除原文，也不能擦除已经进入当前房间的文字。",
      rows: [["目标", memory.title], ["普通检索", "立即拒绝"], ["上下文包", "立即拒绝"], ["原文载荷", "不自动删除"]],
      warning: memory.delivered ? "这条记忆曾被投递到当前房间。隔离会阻止未来投递，但不会谎称已远程擦除当前窗口。" : "隔离成功后，派生清理可以异步完成；读取围栏必须立即生效。",
      confirm: "隔离",
      tone: "primary"
    },
    "restore-isolation": {
      eyebrow: "恢复隔离 · 先预览",
      title: "预览隔离恢复",
      description: "恢复按当前访问策略重新计算。旧上下文包、旧令牌和旧权限不会复活。",
      rows: [["目标", memory.title], ["当前访问策略", `第 ${memory.policyRevision} 版`], ["将撤销", "临时派生阻断"], ["不会恢复", "旧上下文包／旧能力"]],
      warning: "这是预览结果。若访问策略或目标版本变化，当前确认会因版本过期而关闭。",
      confirm: "确认恢复隔离",
      tone: "primary"
    },
    delete: {
      eyebrow: "永久删除 · 不可撤销",
      title: "永久删除影响预览",
      description: "系统已经按当前版本计算闭合范围。请只在这些影响与小林的意图一致时越过最终确认点。",
      rows: [["规范记忆", "1 条"], ["来源锚点", memory.evidence.length ? `${memory.evidence.length} 组` : "0 组"], ["共享原文载荷", memory.id === "mem-7f12" ? "1 个，仅清理本记忆引用" : "无"], ["派生索引", "全文＋向量"], ["灾备边界", "保留到期但禁止恢复上线"]],
      warning: "确认后不可取消、扩大或重新打开。若清理失败，整个闭合范围继续保持隔离；当前房间里已经出现的文字可能仍然可见。",
      confirm: "永久删除这 1 条记忆",
      tone: "danger"
    },
    correct: {
      eyebrow: "修正 · 回到对话",
      title: "与 hide 一起修正",
      description: "第一版不在页面原地覆盖记忆版本。下面的安全引用只包含记忆编号与当前版本，回到对话后再共同形成新版本。",
      rows: [["记忆编号", memory.id], ["当前版本", `第 ${memory.revision} 版`], ["页面会做", "复制安全引用"], ["页面不会做", "直接改写规范正文"]],
      confirm: "复制对话指令",
      tone: "primary"
    }
  };
  const config = configs[action];
  $("#drawerEyebrow").textContent = config.eyebrow;
  content.innerHTML = `
    <h2 class="drawer-title" id="drawerTitle">${escapeHtml(config.title)}</h2>
    <p class="drawer-description" id="drawerDescription">${escapeHtml(config.description)}</p>
    <div class="impact-list">${config.rows.map(([label, value]) => `<div class="impact-row"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`).join("")}</div>
    ${config.warning ? `<div class="impact-warning">${escapeHtml(config.warning)}</div>` : ""}
    <div class="drawer-actions"><button class="button ghost" type="button" data-drawer-cancel>取消</button><button class="button ${config.tone}" type="button" data-drawer-confirm>${escapeHtml(config.confirm)}</button></div>
    <p class="drawer-note">操作绑定 ${escapeHtml(memory.id)} · 第 ${memory.revision} 版 · 访问策略第 ${memory.policyRevision} 版。版本变化时失败关闭。</p>`;
  scrim.hidden = false;
  drawer.hidden = false;
  requestAnimationFrame(() => $("[data-drawer-cancel]", drawer)?.focus());
}

function openEvidenceDrawer() {
  const memory = selectedMemory();
  if (!memory || !memory.evidence.length || state.scenario !== "normal") return;
  state.drawerAction = "evidence";
  state.lastFocus = document.activeElement;
  const drawer = $("#actionDrawer");
  const scrim = $("#scrim");
  const content = $("#drawerContent");
  drawer.classList.add("is-evidence");
  $("#drawerEyebrow").textContent = "完整证据 · 只读";
  content.innerHTML = `
    <h2 class="drawer-title" id="drawerTitle">这条记忆保存的完整证据</h2>
    <p class="drawer-description" id="drawerDescription">下面完整展示系统为本条记忆保存的全部最小必要证据，不省略正文；它不等于整场原对话。</p>
    <div class="impact-list evidence-reader-summary">
      <div class="impact-row"><span>对应记忆</span><strong>${escapeHtml(memory.title)}</strong></div>
      <div class="impact-row"><span>保存片段</span><strong>${memory.evidence.length} 段</strong></div>
      <div class="impact-row"><span>展示完整性</span><strong>已保存证据全部展示</strong></div>
    </div>
    <div class="full-evidence-list">${memory.evidence.map((entry, index) => `
      <article class="full-evidence-entry">
        <div class="full-evidence-index">证据 ${String(index + 1).padStart(2, "0")}</div>
        <dl class="full-evidence-meta">
          <div><dt>来源</dt><dd>${escapeHtml(entry.source || "当前对话（合成）")}</dd></div>
          <div><dt>${entry.messages?.length > 1 ? "参与者" : "说话者"}</dt><dd>${escapeHtml(evidenceParticipants(entry))}</dd></div>
          <div><dt>时间</dt><dd>${escapeHtml(entry.time)}</dd></div>
          <div><dt>截取边界</dt><dd>${escapeHtml(entry.boundary || "单条消息")}</dd></div>
        </dl>
        ${renderFullEvidenceContent(entry, index)}
        <p class="full-evidence-reason"><strong>保留理由</strong>${escapeHtml(entry.note)}</p>
      </article>`).join("")}</div>
    <p class="drawer-note">“完整”指这条记忆已经保存的证据全部呈现；系统不会借此扩大边界、读取无关消息或打开整场原对话。</p>`;
  scrim.hidden = false;
  drawer.hidden = false;
  requestAnimationFrame(() => $("#drawerClose")?.focus());
}

function closeDrawer() {
  $("#scrim").hidden = true;
  $("#actionDrawer").hidden = true;
  $("#actionDrawer").classList.remove("is-evidence");
  state.drawerAction = null;
  state.lastFocus?.focus?.();
}

async function confirmDrawer() {
  const memory = selectedMemory();
  const action = state.drawerAction;
  if (!memory || !action) return;
  if (action === "correct") {
    const prompt = `请与我一起修正记忆 ${memory.id}（当前第 ${memory.revision} 版）：`;
    await copyText(prompt);
    closeDrawer();
    showToast("对话指令已复制；页面没有改写任何记忆。", true);
    return;
  }
  if (action === "archive") memory.state = "ARCHIVED";
  if (action === "restore-active") memory.state = "ACTIVE";
  if (action === "isolate") memory.isolated = true;
  if (action === "restore-isolation") memory.isolated = false;
  if (action === "delete") {
    const index = memories.findIndex(item => item.id === memory.id);
    memories.splice(index, 1);
    runs.unshift({ id: "run-demo", kind: "DELETION", title: "永久删除 1 条记忆", phase: "RECEIVED", time: "刚刚", status: "pending", detail: "最终确认已经成立，执行不可取消；闭合范围保持隔离。" });
    state.selectedId = memories[0]?.id || null;
    closeDrawer();
    renderMemoryList();
    renderMemoryDetail();
    showToast("删除确认已安全接收；执行仍在进行，并未宣称完成。", true);
    setTimeout(() => navigate("runs"), 900);
    return;
  }
  memory.revision += 1;
  memory.updated = "刚刚";
  closeDrawer();
  renderMemoryList();
  renderMemoryDetail();
  showToast(action.includes("restore") ? "恢复已经按当前版本完成。" : action === "archive" ? "已归档；这条记忆不再参与普通检索。" : "隔离已生效；派生清理正在后台继续。", true);
}

function showToast(message, announce = false) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.hidden = false;
  if (announce) $("#liveRegion").textContent = message;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { toast.hidden = true; }, 3800);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const textarea = document.createElement("textarea");
    textarea.value = text;
    textarea.style.position = "fixed";
    textarea.style.opacity = "0";
    document.body.append(textarea);
    textarea.select();
    document.execCommand("copy");
    textarea.remove();
  }
}

document.addEventListener("click", async event => {
  const routeButton = event.target.closest("[data-route]");
  if (routeButton) return navigate(routeButton.dataset.route);

  const row = event.target.closest("[data-memory-id]");
  if (row) {
    state.selectedId = row.dataset.memoryId;
    renderMemoryList();
    renderMemoryDetail();
    if (window.matchMedia("(max-width: 820px)").matches) $("#memoriesView").classList.add("detail-open");
    return;
  }

  const filter = event.target.closest("[data-filter]");
  if (filter) {
    state.filter = filter.dataset.filter;
    $$("[data-filter]").forEach(item => item.classList.toggle("is-active", item === filter));
    renderMemoryList();
    return;
  }

  const action = event.target.closest("[data-action]");
  if (action) return openDrawer(action.dataset.action);
  if (event.target.closest("[data-view-evidence]")) return openEvidenceDrawer();
  if (event.target.closest("[data-drawer-cancel]") || event.target.closest("#drawerClose") || event.target === $("#scrim")) return closeDrawer();
  if (event.target.closest("[data-drawer-confirm]")) return confirmDrawer();
  if (event.target.closest("[data-mobile-back]")) return $("#memoriesView").classList.remove("detail-open");
  if (event.target.closest("[data-reset-scenario]")) {
    state.scenario = "normal";
    $("#scenarioSelect").value = "normal";
    return renderRoute();
  }
  const reviewReturn = event.target.closest("[data-review-return]");
  if (reviewReturn) {
    const text = `请在当前对话继续处理确认单 ${reviewReturn.dataset.reviewReturn}。页面操作不代表确认。`;
    await copyText(text);
    return showToast("安全引用已复制；请回到当前对话继续。", true);
  }
  const reviewCancel = event.target.closest("[data-review-cancel]");
  if (reviewCancel) {
    const review = reviews.find(item => item.id === reviewCancel.dataset.reviewCancel);
    if (review) review.state = "CANCELLED";
    renderReviews();
    return showToast("确认单已取消；终态不会重新变回待处理。", true);
  }
});

$("#memorySearch").addEventListener("input", event => {
  state.query = event.target.value;
  clearTimeout(state.searchTimer);
  state.searchTimer = setTimeout(renderMemoryList, 250);
});

$("#scenarioSelect").addEventListener("change", event => {
  state.scenario = event.target.value;
  renderRoute();
});

$("#sortButton").addEventListener("click", event => {
  state.sortNewest = !state.sortNewest;
  event.currentTarget.textContent = state.sortNewest ? "最近变化" : "较早在前";
  renderMemoryList();
});

$("#mobileMenuButton").addEventListener("click", event => {
  const rail = $("#sideRail");
  const open = rail.classList.toggle("is-open");
  event.currentTarget.setAttribute("aria-expanded", String(open));
});

document.addEventListener("keydown", event => {
  if (event.key === "/" && !["INPUT", "TEXTAREA", "SELECT"].includes(document.activeElement.tagName)) {
    event.preventDefault();
    $("#memorySearch").focus();
  }
  if (event.key === "Escape") {
    if (!$("#actionDrawer").hidden) closeDrawer();
    $("#sideRail").classList.remove("is-open");
    $("#mobileMenuButton").setAttribute("aria-expanded", "false");
  }
  if (!$("#actionDrawer").hidden && event.key === "Tab") {
    const focusable = $$("button:not([disabled]),a[href],input:not([disabled]),select:not([disabled])", $("#actionDrawer"));
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
});

window.addEventListener("hashchange", () => {
  const route = window.location.hash.replace("#", "");
  if (routeMeta[route]) { state.route = route; renderRoute(); }
});

window.addEventListener("resize", () => {
  if (!window.matchMedia("(max-width: 820px)").matches) $("#memoriesView").classList.remove("detail-open");
});

const initialRoute = window.location.hash.replace("#", "");
if (routeMeta[initialRoute]) state.route = initialRoute;
renderRoute();
