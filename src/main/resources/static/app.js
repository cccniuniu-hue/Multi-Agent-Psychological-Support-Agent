const state = {
  auth: {
    username: "student",
    password: "student123"
  },
  sessionId: null,
  sending: false,
  modelName: "deepseek-flash",
  isAdmin: false
};

const els = {
  mainPanel: document.querySelector("#mainPanel"),
  serviceState: document.querySelector("#serviceState"),
  modelState: document.querySelector("#modelState"),
  loginForm: document.querySelector("#loginForm"),
  username: document.querySelector("#username"),
  password: document.querySelector("#password"),
  loginState: document.querySelector("#loginState"),
  accountPanel: document.querySelector("#accountPanel"),
  activeAccount: document.querySelector("#activeAccount"),
  activeRole: document.querySelector("#activeRole"),
  switchAccount: document.querySelector("#switchAccount"),
  studentCompanionPanel: document.querySelector("#studentCompanionPanel"),
  userMemoryPanel: document.querySelector("#userMemoryPanel"),
  userMemoryState: document.querySelector("#userMemoryState"),
  userMemoryList: document.querySelector("#userMemoryList"),
  refreshMemory: document.querySelector("#refreshMemory"),
  adminSidePanel: document.querySelector("#adminSidePanel"),
  sideHighRisk: document.querySelector("#sideHighRisk"),
  sideMailFailed: document.querySelector("#sideMailFailed"),
  sideReports: document.querySelector("#sideReports"),
  chatHead: document.querySelector("#chatHead"),
  profileText: document.querySelector("#profileText"),
  sessionBadge: document.querySelector("#sessionBadge"),
  newSessionButton: document.querySelector("#newSessionButton"),
  messages: document.querySelector("#messages"),
  chatForm: document.querySelector("#chatForm"),
  messageInput: document.querySelector("#messageInput"),
  sendButton: document.querySelector("#sendButton"),
  reportsPanel: document.querySelector(".reports-panel"),
  reportsTitle: document.querySelector("#reportsTitle"),
  reportsCaption: document.querySelector("#reportsCaption"),
  reports: document.querySelector("#reports"),
  refreshReports: document.querySelector("#refreshReports"),
  adminDashboard: document.querySelector("#adminDashboard"),
  adminStats: document.querySelector("#adminStats"),
  adminCharts: document.querySelector("#adminCharts"),
  adminReportRows: document.querySelector("#adminReportRows"),
  excelRows: document.querySelector("#excelRows"),
  emailRows: document.querySelector("#emailRows"),
  traceRows: document.querySelector("#traceRows"),
  adminRefresh: document.querySelector("#adminRefresh"),
  knowledgeUploadForm: document.querySelector("#knowledgeUploadForm"),
  knowledgeFile: document.querySelector("#knowledgeFile"),
  knowledgeUploadState: document.querySelector("#knowledgeUploadState"),
  conversationOverlay: document.querySelector("#conversationOverlay"),
  conversationKicker: document.querySelector("#conversationKicker"),
  conversationTitle: document.querySelector("#conversationTitle"),
  conversationMeta: document.querySelector("#conversationMeta"),
  conversationMessages: document.querySelector("#conversationMessages"),
  closeConversation: document.querySelector("#closeConversation")
};

function authHeader() {
  const token = btoa(`${state.auth.username}:${state.auth.password}`);
  return `Basic ${token}`;
}

function setTone(element, tone) {
  element.classList.remove("ok", "warn", "danger");
  if (tone) {
    element.classList.add(tone);
  }
}

async function api(path, options = {}) {
  const headers = {
    Authorization: authHeader(),
    ...(options.headers || {})
  };
  const response = await fetch(path, { ...options, headers });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `${response.status} ${response.statusText}`);
  }
  return response;
}

function setService(text, ok = true) {
  els.serviceState.textContent = text;
  setTone(els.serviceState, ok ? "ok" : "danger");
}

function setModel(status) {
  state.modelName = status.model || state.modelName;
  const modelLabel = displayModelName(state.modelName);
  const label = status.realModelEnabled
    ? `${status.provider} · ${modelLabel}`
    : "模型配置异常";
  els.modelState.textContent = label;
  setTone(els.modelState, status.realModelEnabled ? "ok" : "warn");
}

function setLogin(text, ok = true) {
  els.loginState.textContent = text;
  setTone(els.loginState, ok ? "ok" : "danger");
}

function showLoginForm() {
  state.isAdmin = false;
  document.body.classList.remove("admin-view");
  els.loginForm.hidden = false;
  els.accountPanel.hidden = true;
  els.studentCompanionPanel.hidden = false;
  els.userMemoryPanel.hidden = true;
  els.userMemoryList.innerHTML = "";
  els.adminSidePanel.hidden = true;
  els.reportsPanel.hidden = true;
  els.reports.innerHTML = "";
  els.adminDashboard.hidden = true;
  els.chatHead.hidden = false;
  els.messages.hidden = false;
  els.chatForm.hidden = false;
  els.mainPanel.classList.remove("admin-mode");
  closeConversation();
}

function isAdminProfile(profile) {
  return profile.roles?.some((role) => role.authority === "ROLE_ADMIN");
}

function accountName(profile) {
  return isAdminProfile(profile) ? profile.displayName : profile.username;
}

function showAccountPanel(profile) {
  const isAdmin = isAdminProfile(profile);
  els.activeAccount.textContent = accountName(profile);
  els.activeRole.textContent = isAdmin ? "管理员账号" : "学生账号";
  els.loginForm.hidden = true;
  els.accountPanel.hidden = false;
}

function showEmpty() {
  if (!els.messages.children.length) {
    const empty = document.createElement("section");
    empty.className = "empty";
    empty.innerHTML = `
      <div class="empty-visual">
        <img src="/assets/mindbridge-campus-companion.png" alt="">
      </div>
      <div class="empty-copy">
        <p class="eyebrow">MindBridge Companion</p>
        <h3>把今天的想法放在这里</h3>
        <p>可以聊学习计划、概念理解、校园生活，也可以把一团乱的心情慢慢拆开。</p>
      </div>
      <div class="empty-prompts">
        <button type="button" class="prompt-chip" data-prompt="帮我制定一个今晚两小时的学习计划">今晚学习计划</button>
        <button type="button" class="prompt-chip" data-prompt="用容易理解的方式解释一下 PCA 线性降维">解释 PCA</button>
        <button type="button" class="prompt-chip" data-prompt="我最近有点焦虑，想先把原因写清楚">梳理焦虑</button>
      </div>
    `;
    els.messages.append(empty);
  }
}

function showStudentChat(profile) {
  // 学生登录后进入聊天工作区，后台记录和统计面板全部隐藏。
  state.isAdmin = false;
  document.body.classList.remove("admin-view");
  els.mainPanel.classList.remove("admin-mode");
  els.studentCompanionPanel.hidden = false;
  els.userMemoryPanel.hidden = false;
  els.adminSidePanel.hidden = true;
  els.adminDashboard.hidden = true;
  els.chatHead.hidden = false;
  els.messages.hidden = false;
  els.chatForm.hidden = false;
  els.profileText.textContent = accountName(profile);
  showEmpty();
}

function showAdminDashboard(profile) {
  // 管理员只看后台数据，不显示学生聊天输入框。
  state.isAdmin = true;
  document.body.classList.add("admin-view");
  els.mainPanel.classList.add("admin-mode");
  els.studentCompanionPanel.hidden = true;
  els.userMemoryPanel.hidden = true;
  els.adminSidePanel.hidden = true;
  els.chatHead.hidden = true;
  els.messages.hidden = true;
  els.chatForm.hidden = true;
  els.adminDashboard.hidden = false;
  els.profileText.textContent = `${accountName(profile)} (${profile.username})`;
  els.adminStats.innerHTML = "";
  els.adminCharts.innerHTML = "";
  els.adminReportRows.innerHTML = "";
  els.excelRows.innerHTML = "";
  els.emailRows.innerHTML = "";
  els.traceRows.innerHTML = "";
}

function clearEmpty() {
  const empty = els.messages.querySelector(".empty");
  if (empty) empty.remove();
}

function usePrompt(prompt) {
  if (!prompt || state.isAdmin) return;
  els.messageInput.value = prompt;
  els.messageInput.focus();
}

function addMessage(role, content = "") {
  clearEmpty();
  const bubble = document.createElement("div");
  bubble.className = `bubble ${role}`;

  const avatar = document.createElement("div");
  avatar.className = "bubble-avatar";
  avatar.textContent = role === "user" ? "你" : "AI";

  const body = document.createElement("div");
  body.className = "bubble-content";
  bubble.append(avatar, body);

  if (role === "assistant") {
    bubble.dataset.raw = content;
    renderAssistantContent(bubble);
  } else {
    body.textContent = content;
  }

  if (role === "assistant") {
    const actions = document.createElement("div");
    actions.className = "message-actions";
    actions.innerHTML = `
      <button type="button" data-copy-message>复制</button>
      <button type="button" data-follow-up="请继续展开刚才的回答，并给一个具体例子。">继续展开</button>
      <button type="button" data-follow-up="请把刚才的回答整理成更清晰的要点。">整理要点</button>
    `;
    bubble.append(actions);
  }

  els.messages.append(bubble);
  els.messages.scrollTop = els.messages.scrollHeight;
  return bubble;
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderAssistantContent(bubble) {
  const body = bubble.querySelector(".bubble-content");
  const raw = bubble.dataset.raw || "";
  body.innerHTML = escapeHtml(raw)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/\n/g, "<br>");
}

function formatDate(value) {
  return value ? new Date(value).toLocaleString() : "";
}

function durationLabel(start, end) {
  if (!start || !end) return "--";
  const ms = Math.max(0, new Date(end).getTime() - new Date(start).getTime());
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function shortId(value) {
  return value ? value.slice(0, 8) : "--";
}

function agentLabel(agent) {
  return {
    MEMORY_AGENT: "Memory",
    SUPERVISOR_AGENT: "Supervisor",
    KNOWLEDGE_AGENT: "Knowledge",
    RISK_GUARDIAN_AGENT: "Risk Guardian",
    COMPANION_AGENT: "Companion",
    COUNSELOR_AGENT: "Counselor"
  }[agent] || agent || "Agent";
}

function actionLabel(action) {
  return {
    READ_MEMORY: "读取记忆",
    ROUTE_INTENT: "意图路由",
    RETRIEVE_KNOWLEDGE: "知识检索",
    ASSESS_RISK: "风险评估",
    PLAN_RESPONSE: "回复规划"
  }[action] || action || "执行";
}

function displayModelName(model) {
  return model || "deepseek-flash";
}

function assistantName() {
  return displayModelName(state.modelName);
}

function setSessionBadge(text, tone) {
  els.sessionBadge.textContent = text;
  els.sessionBadge.classList.toggle("high", tone === "high");
  els.sessionBadge.classList.toggle("medium", tone === "medium");
}

function renderReports(items) {
  els.reports.innerHTML = "";
  if (!items.length) {
    const empty = document.createElement("p");
    empty.className = "state";
    empty.textContent = "暂无报告";
    els.reports.append(empty);
    return;
  }

  items.forEach((item) => {
    const report = document.createElement("article");
    report.className = `report ${item.sessionId ? "is-clickable" : "is-disabled"}`;
    report.tabIndex = item.sessionId ? 0 : -1;
    report.setAttribute("role", item.sessionId ? "button" : "article");
    if (item.sessionId) {
      report.setAttribute("aria-label", `查看 ${item.username} 的完整对话`);
      report.addEventListener("click", () => openConversation(item));
      report.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          openConversation(item);
        }
      });
    }
    const createdAt = formatDate(item.createdAt);

    const top = document.createElement("div");
    top.className = "report-top";

    const title = document.createElement("div");
    title.className = "report-title";
    title.textContent = `${item.emotion} / ${item.intent}`;

    const badge = document.createElement("div");
    badge.className = `badge ${item.riskLevel === "HIGH" ? "high" : item.riskLevel === "MEDIUM" ? "medium" : ""}`;
    badge.textContent = item.riskLevel;

    const meta = document.createElement("div");
    meta.className = "report-meta";
    meta.textContent = createdAt;

    const summary = document.createElement("div");
    summary.className = "report-summary";
    summary.textContent = item.summary || "已记录";

    const hint = document.createElement("div");
    hint.className = "report-hint";
    hint.textContent = item.sessionId ? "点击查看完整对话" : "无会话记录";

    top.append(title, badge);
    report.append(top, meta, summary, hint);
    els.reports.append(report);
  });
}

function memoryTypeLabel(type) {
  return {
    PREFERENCE: "偏好",
    COMMUNICATION_STYLE: "沟通方式",
    SUPPORT_NEED: "支持需求",
    PERSONAL_CONTEXT: "个人背景",
    WELLBEING_PATTERN: "状态模式"
  }[type] || type || "画像";
}

function renderUserMemories(items) {
  els.userMemoryList.innerHTML = "";
  if (!items.length) {
    els.userMemoryState.textContent = "尚未形成画像";
    const empty = document.createElement("p");
    empty.className = "empty-detail";
    empty.textContent = "暂无可复用偏好";
    els.userMemoryList.append(empty);
    return;
  }

  els.userMemoryState.textContent = `${items.length} 条已保存`;
  items.slice(0, 8).forEach((item) => {
    const memory = document.createElement("article");
    memory.className = "memory-item";

    const top = document.createElement("div");
    top.className = "memory-item-top";

    const badge = document.createElement("span");
    badge.className = "memory-type";
    badge.textContent = memoryTypeLabel(item.type);

    const action = document.createElement("button");
    action.type = "button";
    action.className = "memory-delete";
    action.dataset.memoryDelete = item.id;
    action.textContent = "删除";

    const summary = document.createElement("p");
    summary.className = "memory-summary";
    summary.textContent = item.summary;

    const meta = document.createElement("p");
    meta.className = "memory-meta";
    meta.textContent = `${Math.round((item.confidence || 0) * 100)}% · ${formatDate(item.lastSeenAt)}`;

    top.append(badge, action);
    memory.append(top, summary, meta);
    if (item.evidence) {
      const evidence = document.createElement("p");
      evidence.className = "memory-evidence";
      evidence.textContent = item.evidence;
      memory.append(evidence);
    }
    els.userMemoryList.append(memory);
  });
}

function statusTone(status) {
  if (status === "SUCCESS" || status === "LOW") return "ok";
  if (status === "FAILED" || status === "HIGH") return "danger";
  if (status === "PENDING" || status === "MEDIUM") return "warn";
  return "";
}

function statusLabel(status) {
  return status || "SKIPPED";
}

function metricCard(label, value, tone) {
  const card = document.createElement("article");
  card.className = `metric-card ${tone || ""}`;
  const number = document.createElement("strong");
  number.textContent = value;
  const text = document.createElement("span");
  text.textContent = label;
  card.append(number, text);
  return card;
}

function countBy(items, key) {
  return items.reduce((acc, item) => {
    const value = item[key] || "UNKNOWN";
    acc[value] = (acc[value] || 0) + 1;
    return acc;
  }, {});
}

function chartCard(title, rows) {
  const total = rows.reduce((sum, row) => sum + row.value, 0);
  const card = document.createElement("article");
  card.className = "chart-card";
  const heading = document.createElement("h3");
  heading.textContent = title;
  card.append(heading);

  rows.forEach((row) => {
    const percent = total ? Math.round((row.value / total) * 100) : 0;
    const line = document.createElement("div");
    line.className = "chart-row";

    const label = document.createElement("span");
    label.textContent = row.label;

    const track = document.createElement("div");
    track.className = "chart-track";
    const fill = document.createElement("div");
    fill.className = `chart-fill ${row.tone || ""}`;
    fill.style.width = `${percent}%`;
    track.append(fill);

    const value = document.createElement("strong");
    value.textContent = `${row.value}`;

    line.append(label, track, value);
    card.append(line);
  });
  return card;
}

function emptyTable(text) {
  const empty = document.createElement("p");
  empty.className = "empty-detail";
  empty.textContent = text;
  return empty;
}

function adminActionRow(title, status, statusClass, meta, summary, onClick) {
  const row = document.createElement("button");
  row.type = "button";
  row.className = "admin-row report-row-action";
  row.addEventListener("click", onClick);

  const main = document.createElement("div");
  main.className = "admin-row-main";

  const titleNode = document.createElement("strong");
  titleNode.className = "admin-row-title";
  titleNode.textContent = title;

  const badge = document.createElement("span");
  badge.className = `admin-status ${statusClass || ""}`;
  badge.textContent = status;

  const metaNode = document.createElement("div");
  metaNode.className = "admin-row-meta";
  metaNode.textContent = meta;

  const summaryNode = document.createElement("div");
  summaryNode.className = "admin-row-summary";
  summaryNode.textContent = summary ?? "点击查看详情";

  main.append(titleNode, badge);
  row.append(main, metaNode, summaryNode);
  return row;
}

function renderAdminStats(reports, excelRecords, alerts, traces) {
  // 顶部指标卡用于快速判断报告量、风险量和工具链运行情况。
  els.adminStats.innerHTML = "";
  const highCount = reports.filter((item) => item.riskLevel === "HIGH").length;
  const successEmails = alerts.filter((item) => item.status === "SUCCESS").length;
  const failedEmails = alerts.filter((item) => item.status === "FAILED").length;
  els.sideHighRisk.textContent = highCount;
  els.sideMailFailed.textContent = failedEmails;
  els.sideReports.textContent = reports.length;
  els.adminStats.append(
    metricCard("风险/咨询报告", reports.length, ""),
    metricCard("高风险记录", highCount, "danger"),
    metricCard("Excel 写入", excelRecords.length, "ok"),
    metricCard("邮件成功", successEmails, "ok"),
    metricCard("运行轨迹", traces.length, "")
  );
}

function renderAdminCharts(reports, excelRecords, alerts) {
  // 图表大屏完全由后台接口数据计算，不在前端重新做风险判断。
  els.adminCharts.innerHTML = "";
  const risk = countBy(reports, "riskLevel");
  const emotion = countBy(reports, "emotion");
  const intent = countBy(reports, "intent");
  const mail = countBy(alerts, "status");

  els.adminCharts.append(
    chartCard("风险等级", [
      { label: "LOW", value: risk.LOW || 0, tone: "ok" },
      { label: "MEDIUM", value: risk.MEDIUM || 0, tone: "warn" },
      { label: "HIGH", value: risk.HIGH || 0, tone: "danger" }
    ]),
    chartCard("情绪分布", [
      { label: "NORMAL", value: emotion.NORMAL || 0, tone: "ok" },
      { label: "ANXIETY", value: emotion.ANXIETY || 0, tone: "warn" },
      { label: "DEPRESSED", value: emotion.DEPRESSED || 0, tone: "warn" },
      { label: "HIGH_RISK", value: emotion.HIGH_RISK || 0, tone: "danger" }
    ]),
    chartCard("意图类型", [
      { label: "CHAT", value: intent.CHAT || 0, tone: "ok" },
      { label: "CONSULT", value: intent.CONSULT || 0, tone: "warn" },
      { label: "RISK", value: intent.RISK || 0, tone: "danger" }
    ]),
    chartCard("工具状态", [
      { label: "Excel", value: excelRecords.length, tone: "ok" },
      { label: "邮件成功", value: mail.SUCCESS || 0, tone: "ok" },
      { label: "邮件失败", value: mail.FAILED || 0, tone: "danger" }
    ])
  );
}

function renderAdminReportRows(reports) {
  els.adminReportRows.innerHTML = "";
  if (!reports.length) {
    els.adminReportRows.append(emptyTable("暂无对话记录"));
    return;
  }

  reports.slice(0, 20).forEach((item) => {
    const row = adminActionRow(
      item.username,
      item.riskLevel,
      statusTone(item.riskLevel),
      `${item.emotion} / ${item.intent} · ${formatDate(item.createdAt)}`,
      item.summary || "点击查看完整对话",
      () => openConversation(item)
    );
    els.adminReportRows.append(row);
  });
}

function renderExcelRows(records) {
  els.excelRows.innerHTML = "";
  if (!records.length) {
    els.excelRows.append(emptyTable("暂无 Excel 写入数据"));
    return;
  }

  records.slice(0, 20).forEach((item) => {
    const row = adminActionRow(
      `#${item.reportId} · ${item.username}`,
      statusLabel(item.excelStatus),
      statusTone(item.excelStatus),
      `${item.emotion} / ${item.riskLevel} · ${formatDate(item.createdAt)}`,
      item.summary || item.content || "点击查看 Excel 写入详情",
      () => openExcelRecord(item)
    );
    els.excelRows.append(row);
  });
}

function renderEmailRows(records) {
  els.emailRows.innerHTML = "";
  if (!records.length) {
    els.emailRows.append(emptyTable("暂无邮件发送记录"));
    return;
  }

  records.slice(0, 20).forEach((item) => {
    const row = adminActionRow(
      `报告 #${item.reportId} · ${item.username}`,
      statusLabel(item.status),
      statusTone(item.status),
      `${item.recipient} · ${item.attempts} 次 · ${formatDate(item.updatedAt)}`,
      item.errorMessage || item.summary || "点击查看邮件发送详情",
      () => openEmailRecord(item)
    );
    els.emailRows.append(row);
  });
}

function renderAdminTraceRows(traces) {
  els.traceRows.innerHTML = "";
  if (!traces.length) {
    els.traceRows.append(emptyTable("暂无运行轨迹"));
    return;
  }

  traces.slice(0, 20).forEach((item) => {
    const row = adminActionRow(
      item.username,
      `${item.stepCount} 步`,
      statusTone(item.riskLevel),
      `${item.intent || "UNKNOWN"} / ${item.riskLevel} · ${durationLabel(item.startedAt, item.completedAt)}`,
      item.input || `Trace #${shortId(item.traceId)}`,
      () => openRunTrace(item)
    );
    els.traceRows.append(row);
  });
}

function renderAdminDashboard(reports, excelRecords, alerts, traces) {
  renderAdminStats(reports, excelRecords, alerts, traces);
  renderAdminCharts(reports, excelRecords, alerts);
  renderAdminReportRows(reports);
  renderExcelRows(excelRecords);
  renderEmailRows(alerts);
  renderAdminTraceRows(traces);
}

function startNewSession() {
  state.sessionId = null;
  els.messages.innerHTML = "";
  setSessionBadge("READY");
  showEmpty();
  els.messageInput.focus();
}

function roleLabel(role) {
  if (role === "USER") return "学生";
  if (role === "ASSISTANT") return assistantName();
  return "系统";
}

function detailItem(label, value, className) {
  const item = document.createElement("div");
  item.className = `record-detail-item ${className || ""}`;

  const key = document.createElement("span");
  key.textContent = label;

  const val = document.createElement("strong");
  val.textContent = value ?? "无";

  item.append(key, val);
  return item;
}

function renderRecordDetail({ kicker, title, meta, items, summary, conversationRecord }) {
  els.conversationOverlay.hidden = false;
  els.conversationKicker.textContent = kicker;
  els.conversationTitle.textContent = title;
  els.conversationMeta.textContent = meta;
  els.conversationMessages.innerHTML = "";

  const detail = document.createElement("section");
  detail.className = "record-detail";
  items.forEach((item) => {
    detail.append(detailItem(item.label, item.value, item.className));
  });

  if (summary) {
    const summaryBlock = document.createElement("article");
    summaryBlock.className = "record-detail-summary";
    const label = document.createElement("span");
    label.textContent = "内容摘要";
    const content = document.createElement("p");
    content.textContent = summary;
    summaryBlock.append(label, content);
    detail.append(summaryBlock);
  }

  if (conversationRecord?.sessionId) {
    const action = document.createElement("button");
    action.type = "button";
    action.className = "ghost detail-action";
    action.textContent = "查看完整对话";
    action.addEventListener("click", () => openConversation(conversationRecord));
    detail.append(action);
  }

  els.conversationMessages.append(detail);
}

function openExcelRecord(record) {
  renderRecordDetail({
    kicker: `Excel 写入 · 报告 #${record.reportId}`,
    title: `${record.username} 的 Excel 写入数据`,
    meta: "管理员视图",
    conversationRecord: record,
    summary: record.summary || record.content,
    items: [
      { label: "学生账号", value: record.username },
      { label: "报告编号", value: `#${record.reportId}` },
      { label: "写入状态", value: statusLabel(record.excelStatus), className: statusTone(record.excelStatus) },
      { label: "意图类型", value: record.intent },
      { label: "情绪识别", value: record.emotion },
      { label: "风险等级", value: record.riskLevel, className: statusTone(record.riskLevel) },
      { label: "置信度", value: record.confidence },
      { label: "原始输入", value: record.content },
      { label: "写入时间", value: formatDate(record.createdAt) }
    ]
  });
}

function openEmailRecord(record) {
  renderRecordDetail({
    kicker: `邮件发送 · 报告 #${record.reportId}`,
    title: `${record.username} 的邮件发送记录`,
    meta: "管理员视图",
    conversationRecord: record,
    summary: record.errorMessage || record.summary,
    items: [
      { label: "学生账号", value: record.username },
      { label: "报告编号", value: `#${record.reportId}` },
      { label: "收件人", value: record.recipient },
      { label: "发送状态", value: statusLabel(record.status), className: statusTone(record.status) },
      { label: "尝试次数", value: `${record.attempts} 次` },
      { label: "风险等级", value: record.riskLevel, className: statusTone(record.riskLevel) },
      { label: "创建时间", value: formatDate(record.createdAt) },
      { label: "更新时间", value: formatDate(record.updatedAt) }
    ]
  });
}

function traceMetaItem(label, value, tone) {
  const item = document.createElement("div");
  item.className = `trace-meta-item ${tone || ""}`;
  const key = document.createElement("span");
  key.textContent = label;
  const content = document.createElement("strong");
  content.textContent = value ?? "无";
  item.append(key, content);
  return item;
}

function traceContextBlock(label, value) {
  const block = document.createElement("article");
  block.className = "trace-context-block";
  const title = document.createElement("span");
  title.textContent = label;
  const content = document.createElement("p");
  content.textContent = value || "无";
  block.append(title, content);
  return block;
}

function renderTraceCard(trace, compact = false) {
  const card = document.createElement("article");
  card.className = `trace-card ${compact ? "compact" : ""}`;

  const head = document.createElement("div");
  head.className = "trace-card-head";

  const title = document.createElement("div");
  title.className = "trace-title-block";
  const kicker = document.createElement("p");
  kicker.className = "eyebrow";
  kicker.textContent = `Run Trace #${shortId(trace.traceId)}`;
  const heading = document.createElement("h3");
  heading.textContent = trace.input || "运行轨迹";
  const meta = document.createElement("p");
  meta.className = "state";
  meta.textContent = `${formatDate(trace.startedAt)} · ${durationLabel(trace.startedAt, trace.completedAt)}`;
  title.append(kicker, heading, meta);

  const badges = document.createElement("div");
  badges.className = "trace-badges";
  badges.append(
    traceMetaItem("意图", trace.intent || "UNKNOWN"),
    traceMetaItem("风险", trace.riskLevel, statusTone(trace.riskLevel)),
    traceMetaItem("步骤", `${trace.stepCount} 步`),
    traceMetaItem("回复 Agent", agentLabel(trace.responseAgent))
  );

  head.append(title, badges);
  card.append(head);

  if (!compact) {
    const context = document.createElement("div");
    context.className = "trace-context-grid";
    context.append(
      traceContextBlock("记忆摘要", trace.memoryBrief),
      traceContextBlock("知识查询", trace.knowledgeQuery),
      traceContextBlock("回复计划", trace.responsePlan)
    );
    card.append(context);
  }

  const steps = document.createElement("div");
  steps.className = "trace-steps";
  if (!trace.steps?.length) {
    steps.append(emptyTable("暂无步骤记录"));
  } else {
    trace.steps.forEach((step) => {
      const row = document.createElement("article");
      row.className = "trace-step";

      const index = document.createElement("div");
      index.className = "trace-step-index";
      index.textContent = step.step;

      const body = document.createElement("div");
      body.className = "trace-step-body";

      const top = document.createElement("div");
      top.className = "trace-step-top";
      const agent = document.createElement("strong");
      agent.textContent = agentLabel(step.agent);
      const action = document.createElement("span");
      action.textContent = actionLabel(step.action);
      const time = document.createElement("span");
      time.textContent = formatDate(step.createdAt);
      top.append(agent, action, time);

      const observation = document.createElement("p");
      observation.textContent = step.observation || "无 observation";

      body.append(top, observation);
      row.append(index, body);
      steps.append(row);
    });
  }
  card.append(steps);
  return card;
}

function renderConversationTraces(traces) {
  const section = document.createElement("section");
  section.className = "conversation-traces";

  const head = document.createElement("div");
  head.className = "conversation-traces-head";
  const title = document.createElement("h3");
  title.textContent = "运行轨迹";
  const count = document.createElement("p");
  count.className = "state";
  count.textContent = traces.length ? `${traces.length} 轮 Agent loop` : "暂无运行轨迹";
  head.append(title, count);
  section.append(head);

  if (!traces.length) {
    section.append(emptyTable("暂无运行轨迹"));
    return section;
  }

  traces.forEach((trace) => {
    section.append(renderTraceCard(trace, true));
  });
  return section;
}

function renderRunTraceDetail(trace) {
  els.conversationOverlay.hidden = false;
  els.conversationKicker.textContent = `${trace.username} · ${trace.sessionId}`;
  els.conversationTitle.textContent = `运行轨迹 #${shortId(trace.traceId)}`;
  els.conversationMeta.textContent = `${trace.displayName} · ${formatDate(trace.startedAt)}`;
  els.conversationMessages.innerHTML = "";

  const detail = renderTraceCard(trace);
  const action = document.createElement("button");
  action.type = "button";
  action.className = "ghost detail-action";
  action.textContent = "查看完整对话";
  action.addEventListener("click", () => openConversation(trace));
  detail.append(action);
  els.conversationMessages.append(detail);
}

function renderConversation(conversation) {
  els.conversationKicker.textContent = `${conversation.username} · ${conversation.sessionId}`;
  els.conversationTitle.textContent = conversation.title || "对话记录";
  els.conversationMeta.textContent = `${conversation.displayName} 与 ${assistantName()} 的完整对话`;
  els.conversationMessages.innerHTML = "";

  if (!conversation.messages.length) {
    const empty = document.createElement("p");
    empty.className = "empty-detail";
    empty.textContent = "这次会话还没有消息。";
    els.conversationMessages.append(empty);
  } else {
    conversation.messages.forEach((message) => {
      const item = document.createElement("article");
      item.className = `conversation-message ${message.role.toLowerCase()}`;

      const top = document.createElement("div");
      top.className = "conversation-message-top";

      const role = document.createElement("strong");
      role.textContent = roleLabel(message.role);

      const time = document.createElement("span");
      time.textContent = formatDate(message.createdAt);

      const content = document.createElement("div");
      content.className = "conversation-message-content";
      content.textContent = message.content;

      top.append(role, time);
      item.append(top, content);
      els.conversationMessages.append(item);
    });
  }

  els.conversationMessages.append(renderConversationTraces(conversation.runTraces || []));
}

function showConversationLoading(report) {
  els.conversationOverlay.hidden = false;
  els.conversationKicker.textContent = `${report.username} · ${report.sessionId}`;
  els.conversationTitle.textContent = "正在读取完整对话";
  els.conversationMeta.textContent = "管理员视图";
  els.conversationMessages.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "empty-detail";
  loading.textContent = "加载中...";
  els.conversationMessages.append(loading);
}

async function openConversation(report) {
  if (!report.sessionId) return;
  showConversationLoading(report);
  try {
    const response = await api(`/api/admin/conversations/${encodeURIComponent(report.sessionId)}`);
    const conversation = await response.json();
    renderConversation(conversation);
  } catch (error) {
    els.conversationTitle.textContent = "读取失败";
    els.conversationMeta.textContent = "";
    els.conversationMessages.innerHTML = "";
    const failed = document.createElement("p");
    failed.className = "empty-detail";
    failed.textContent = "无法读取这次对话，请确认管理员账号仍然有效。";
    els.conversationMessages.append(failed);
  }
}

function showRunTraceLoading(record) {
  els.conversationOverlay.hidden = false;
  els.conversationKicker.textContent = `${record.username || "Run Trace"} · ${record.sessionId || ""}`;
  els.conversationTitle.textContent = "正在读取运行轨迹";
  els.conversationMeta.textContent = "管理员视图";
  els.conversationMessages.innerHTML = "";
  const loading = document.createElement("p");
  loading.className = "empty-detail";
  loading.textContent = "加载中...";
  els.conversationMessages.append(loading);
}

async function openRunTrace(record) {
  const traceId = typeof record === "string" ? record : record.traceId;
  if (!traceId) return;
  showRunTraceLoading(typeof record === "string" ? { traceId } : record);
  try {
    const response = await api(`/api/admin/run-traces/${encodeURIComponent(traceId)}`);
    const trace = await response.json();
    renderRunTraceDetail(trace);
  } catch (error) {
    els.conversationTitle.textContent = "读取失败";
    els.conversationMeta.textContent = "";
    els.conversationMessages.innerHTML = "";
    const failed = document.createElement("p");
    failed.className = "empty-detail";
    failed.textContent = "无法读取这条运行轨迹，请确认管理员账号仍然有效。";
    els.conversationMessages.append(failed);
  }
}

function closeConversation() {
  els.conversationOverlay.hidden = true;
}

async function loadProfile() {
  const response = await api("/api/profile");
  const profile = await response.json();
  const isAdmin = profile.roles?.some((role) => role.authority === "ROLE_ADMIN");
  showAccountPanel(profile);
  if (isAdmin) {
    showAdminDashboard(profile);
    els.reportsPanel.hidden = false;
    els.reportsTitle.textContent = "后台记录";
    els.reportsCaption.textContent = "管理员视图";
  } else {
    showStudentChat(profile);
    els.reportsPanel.hidden = true;
    els.reports.innerHTML = "";
    await loadUserMemories();
  }
  setLogin("登录成功");
  return profile;
}

async function loadUserMemories() {
  if (state.isAdmin || els.userMemoryPanel.hidden) return;
  try {
    const response = await api("/api/profile/memory");
    const memories = await response.json();
    renderUserMemories(memories);
  } catch (error) {
    els.userMemoryState.textContent = "画像读取失败";
    els.userMemoryList.innerHTML = "";
  }
}

async function deleteUserMemory(memoryId) {
  try {
    await api(`/api/profile/memory/${encodeURIComponent(memoryId)}`, {
      method: "DELETE"
    });
    await loadUserMemories();
  } catch (error) {
    els.userMemoryState.textContent = "删除失败";
  }
}

async function loadAgentStatus() {
  const response = await api("/api/agent/status");
  const status = await response.json();
  setModel(status);
}

async function loadReports() {
  const response = await api("/api/admin/reports");
  const reports = await response.json();
  renderReports(reports);
  return reports;
}

async function loadExcelRecords() {
  const response = await api("/api/admin/excel-records");
  return response.json();
}

async function loadAlertRecords() {
  const response = await api("/api/admin/alerts");
  return response.json();
}

async function loadRunTraces() {
  const response = await api("/api/admin/run-traces");
  return response.json();
}

async function loadAdminData() {
  // 后台数据并行加载，避免刷新大屏时互相阻塞。
  const [reports, excelRecords, alerts, traces] = await Promise.all([
    loadReports(),
    loadExcelRecords(),
    loadAlertRecords(),
    loadRunTraces()
  ]);
  renderAdminDashboard(reports, excelRecords, alerts, traces);
}

async function uploadKnowledgeFile(event) {
  event.preventDefault();
  const file = els.knowledgeFile.files?.[0];
  if (!file) {
    els.knowledgeUploadState.textContent = "请先选择文件";
    setTone(els.knowledgeUploadState, "warn");
    return;
  }
  // 使用 multipart/form-data 上传原文件，由后端统一解析和写入知识库。
  const body = new FormData();
  body.append("file", file);
  els.knowledgeUploadState.textContent = "正在上传并切分入库...";
  setTone(els.knowledgeUploadState, "warn");
  try {
    const response = await api("/api/admin/knowledge/file", {
      method: "POST",
      body
    });
    const result = await response.json();
    els.knowledgeUploadState.textContent = `${result.source} 已入库 ${result.chunks} 个片段`;
    setTone(els.knowledgeUploadState, "ok");
    els.knowledgeFile.value = "";
  } catch (error) {
    els.knowledgeUploadState.textContent = "上传失败：" + error.message;
    setTone(els.knowledgeUploadState, "danger");
  }
}

async function checkHealth() {
  try {
    const response = await fetch("/actuator/health");
    const body = await response.json();
    setService(body.status === "UP" ? "服务正常" : `服务${body.status}`, body.status === "UP");
  } catch (error) {
    setService("服务不可用", false);
  }
}

async function login(event) {
  event?.preventDefault();
  state.auth.username = els.username.value.trim();
  state.auth.password = els.password.value;
  try {
    const profile = await loadProfile();
    await loadAgentStatus();
    const isAdmin = profile.roles?.some((role) => role.authority === "ROLE_ADMIN");
    if (isAdmin) {
      await loadAdminData();
    }
  } catch (error) {
    showLoginForm();
    setLogin("账号或密码不正确", false);
  }
}

function parseSse(buffer, onEvent) {
  // SSE 可能分片到达，未完成的事件块保留到下一次读取。
  const blocks = buffer.split("\n\n");
  const rest = blocks.pop() || "";
  blocks.forEach((block) => {
    const dataLine = block.split("\n").find((line) => line.startsWith("data:"));
    if (!dataLine) return;
    onEvent(JSON.parse(dataLine.slice(5)));
  });
  return rest;
}

async function sendMessage(event) {
  event.preventDefault();
  // 管理员界面没有聊天职责，即使表单事件被触发也直接忽略。
  if (state.isAdmin) return;
  const message = els.messageInput.value.trim();
  if (!message || state.sending) return;

  state.sending = true;
  els.sendButton.disabled = true;
  setSessionBadge("THINKING", "medium");
  els.messageInput.value = "";
  addMessage("user", message);
  const assistant = addMessage("assistant", "");

  try {
    const response = await api("/api/chat/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: state.sessionId, message })
    });

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = parseSse(buffer, (eventData) => {
        if (eventData.type === "meta") {
          state.sessionId = eventData.sessionId;
        }
        if (eventData.type === "token") {
          assistant.dataset.raw = `${assistant.dataset.raw || ""}${eventData.content}`;
          renderAssistantContent(assistant);
          els.messages.scrollTop = els.messages.scrollHeight;
        }
        if (eventData.type === "error") {
          assistant.dataset.raw = eventData.content || "模型暂时没有返回内容，请稍后重试。";
          renderAssistantContent(assistant);
          els.messages.scrollTop = els.messages.scrollHeight;
        }
      });
    }

    if (!assistant.dataset.raw) {
      assistant.dataset.raw = "模型暂时没有返回内容，请稍后重试。";
      renderAssistantContent(assistant);
    }

    if (!els.reportsPanel.hidden) {
      await loadReports();
    }
    await loadUserMemories();
  } catch (error) {
    assistant.dataset.raw = "请求失败，请确认后端已启动并且账号正确。";
    renderAssistantContent(assistant);
  } finally {
    state.sending = false;
    els.sendButton.disabled = false;
    setSessionBadge("READY");
    els.messageInput.focus();
  }
}

els.loginForm.addEventListener("submit", login);
els.switchAccount.addEventListener("click", () => {
  showLoginForm();
  setLogin("可切换账号");
  els.username.focus();
});
els.chatForm.addEventListener("submit", sendMessage);
document.addEventListener("click", (event) => {
  const adminRefresh = event.target.closest("[data-admin-refresh]");
  if (adminRefresh) {
    loadAdminData();
    return;
  }
  const scrollTarget = event.target.closest("[data-scroll-target]");
  if (scrollTarget) {
    const target = document.querySelector(`#${scrollTarget.dataset.scrollTarget}`);
    target?.scrollIntoView({ behavior: "smooth", block: "center" });
    return;
  }
  const copyButton = event.target.closest("[data-copy-message]");
  if (copyButton) {
    const bubble = copyButton.closest(".bubble");
    navigator.clipboard?.writeText(bubble?.dataset.raw || "");
    copyButton.textContent = "已复制";
    setTimeout(() => {
      copyButton.textContent = "复制";
    }, 1200);
    return;
  }
  const followUp = event.target.closest("[data-follow-up]");
  if (followUp) {
    usePrompt(followUp.dataset.followUp);
    return;
  }
  const memoryDelete = event.target.closest("[data-memory-delete]");
  if (memoryDelete) {
    deleteUserMemory(memoryDelete.dataset.memoryDelete);
    return;
  }
  const chip = event.target.closest(".prompt-chip");
  if (!chip) return;
  usePrompt(chip.dataset.prompt || chip.textContent.trim());
});
els.newSessionButton.addEventListener("click", startNewSession);
els.refreshReports.addEventListener("click", () => {
  if (state.isAdmin) {
    loadAdminData();
  }
});
els.adminRefresh.addEventListener("click", loadAdminData);
els.refreshMemory.addEventListener("click", loadUserMemories);
els.knowledgeUploadForm.addEventListener("submit", uploadKnowledgeFile);
els.closeConversation.addEventListener("click", closeConversation);
els.conversationOverlay.addEventListener("click", (event) => {
  if (event.target === els.conversationOverlay) {
    closeConversation();
  }
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !els.conversationOverlay.hidden) {
    closeConversation();
  }
});

checkHealth();
showEmpty();
login();
