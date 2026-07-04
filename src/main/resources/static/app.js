// DBTower 웹 콘솔 — 프레임워크 없는 정적 SPA.
// 백엔드가 본질인 프로젝트라 프론트는 의존성 0으로 얇게 유지한다 (java -jar 하나로 화면까지).
// 화면 구도는 당근 KDMS Database Insight를 참고: 인스턴스 선택 -> 그래프 드래그로 구간 선택
// -> Top Query 증감(NEW 뱃지) -> 쿼리 클릭 -> 실행계획 + AI 분석.

const $ = (sel) => document.querySelector(sel);

// CSRF: 서버가 XSRF-TOKEN 쿠키로 준 토큰을 변경 요청 헤더로 되돌려준다 (A1)
const csrfToken = () => {
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
};

const api = (path, opts = {}) => {
  const headers = { ...(opts.headers || {}) };
  if (opts.method && opts.method !== "GET") headers["X-XSRF-TOKEN"] = csrfToken();
  return fetch(path, { ...opts, headers }).then((r) => {
    if (r.status === 401) { location.href = "/login.html"; throw new Error("로그인이 필요합니다"); }
    if (!r.ok) return r.text().then((t) => { throw new Error(`${r.status} ${t}`); });
    return r.json();
  });
};

const state = {
  instance: null,      // 선택된 인스턴스 {id, name, type, ...}
  activity: [],        // [{time, qps, avgLatencyMs}]
  dragMode: null,      // 'target' | 'base'
  selections: {},      // {target: {from: Date, to: Date}, base: {...}}
  compareMode: false,  // 마지막 조회가 비교 조회였는지
  currentQuery: null,  // 상세 패널에 열린 쿼리
  lastPlan: null,      // 마지막 EXPLAIN 실행계획 (문의 첨부용)
  lastFindings: [],    // 마지막 규칙 기반 지적
  lastAi: null,        // 마지막 AI 분석
  role: null,          // 로그인 주체의 역할 (ADMIN이면 세션 kill 버튼 노출)
};

// ---------- 유틸 ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// datetime-local 입력값(로컬 시각)과 LocalDateTime(ISO) 사이 변환
const toLocalInput = (date) => {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`;
};
const fmtNum = (v, digits = 2) => v == null ? "-" : Number(v).toLocaleString("ko-KR", { maximumFractionDigits: digits });

// 증감 셀: "target값 (▲ diff)" — KDMS 표기. changePct가 null(base 0)이면 화살표 생략
function deltaCell(base, target, changePct, digits = 2) {
  const t = fmtNum(target, digits);
  if (changePct == null) return `<span class="num">${t}</span>`;
  const diff = target - base;
  const cls = diff >= 0 ? "delta-up" : "delta-down";
  const arrow = diff >= 0 ? "▲" : "▼";
  return `<span class="num">${t} <span class="${cls}">(${arrow} ${fmtNum(Math.abs(diff), digits)})</span></span>`;
}

// ---------- 인스턴스 ----------
async function loadInstances() {
  const list = await api("/api/instances");
  const box = $("#instance-list");
  if (!list.length) {
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다 — POST /api/instances 로 등록하세요.</div>';
    return;
  }
  box.innerHTML = list.map((i) => `
    <div class="instance-card" data-id="${i.id}">
      <div class="instance-name">
        <span class="type-badge type-${esc(i.type)}">${esc(i.type)}</span>
        ${esc(i.name)}
        <span class="health-dot" id="health-${i.id}"></span>
      </div>
      <div class="instance-host">${esc(i.host)}:${i.port} / ${esc(i.dbName)}
        <span class="health-ms" id="healthms-${i.id}"></span></div>
    </div>`).join("");

  box.querySelectorAll(".instance-card").forEach((card) => {
    card.addEventListener("click", () => selectInstance(list.find((i) => i.id == card.dataset.id), card));
  });
  // 첫 인스턴스 자동 선택 — 진입 즉시 대시보드가 차 있게
  const first = box.querySelector(".instance-card");
  if (first) selectInstance(list[0], first);
  // 헬스는 카드 렌더 후 비동기로 채운다 — 죽은 인스턴스가 목록 로딩을 막지 않게
  list.forEach(async (i) => {
    try {
      const h = await api(`/api/instances/${i.id}/health`);
      $(`#health-${i.id}`).classList.add(h.up ? "up" : "down");
      $(`#healthms-${i.id}`).textContent = h.up ? `${h.pingMillis}ms · ${h.version ?? ""}` : h.message;
    } catch { $(`#health-${i.id}`).classList.add("down"); }
  });
}

async function selectInstance(instance, card) {
  state.instance = instance;
  document.querySelectorAll(".instance-card").forEach((c) => c.classList.remove("selected"));
  card.classList.add("selected");
  $("#time-panel").hidden = false;
  $("#result-panel").hidden = false;

  // 기본 구간: 조회 = 최근 30분, 비교 = 그 직전 30분
  const now = new Date();
  $("#target-to").value = toLocalInput(now);
  $("#target-from").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-to").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-from").value = toLocalInput(new Date(now - 60 * 60000));
  state.selections = {};

  await Promise.all([loadActivity(), runQuery(), loadSlow(), loadReplication(), loadWaitEvents(), loadSessions()]);
}

// ---------- 활동 그래프 (드래그 구간 선택) ----------
async function loadActivity() {
  const now = new Date();
  const from = toLocalInput(new Date(now - 3 * 3600 * 1000)); // 최근 3시간
  const to = toLocalInput(now);
  state.activity = await api(`/api/instances/${state.instance.id}/activity?from=${from}&to=${to}`);
  drawChart();
}

const CHART = { w: 1000, h: 180, padL: 46, padR: 10, padT: 12, padB: 22 };

function chartScales() {
  const pts = state.activity;
  const t0 = new Date(pts[0].time).getTime();
  const t1 = new Date(pts[pts.length - 1].time).getTime();
  const maxQ = Math.max(...pts.map((p) => p.qps), 1);
  const x = (t) => CHART.padL + (t - t0) / Math.max(t1 - t0, 1) * (CHART.w - CHART.padL - CHART.padR);
  const y = (q) => CHART.h - CHART.padB - q / maxQ * (CHART.h - CHART.padT - CHART.padB);
  const invX = (px) => t0 + (px - CHART.padL) / (CHART.w - CHART.padL - CHART.padR) * (t1 - t0);
  return { t0, t1, maxQ, x, y, invX };
}

function drawChart() {
  const svg = $("#activity-chart");
  const pts = state.activity;
  svg.setAttribute("viewBox", `0 0 ${CHART.w} ${CHART.h}`);
  if (pts.length < 2) {
    svg.innerHTML = "";
    $("#chart-empty").hidden = false;
    return;
  }
  $("#chart-empty").hidden = true;
  const s = chartScales();

  // 선택 구간 하이라이트 (조회=초록, 비교=주황)
  const selRect = (sel, color) => sel
    ? `<rect x="${s.x(sel.from.getTime())}" y="${CHART.padT}"
        width="${Math.max(s.x(sel.to.getTime()) - s.x(sel.from.getTime()), 2)}"
        height="${CHART.h - CHART.padT - CHART.padB}" fill="${color}" opacity="0.22"/>` : "";

  // y축 눈금 3개 + 시간 라벨 4개
  const yTicks = [0, 0.5, 1].map((r) => {
    const q = s.maxQ * r;
    return `<line x1="${CHART.padL}" y1="${s.y(q)}" x2="${CHART.w - CHART.padR}" y2="${s.y(q)}" stroke="#eef0f3"/>
            <text x="${CHART.padL - 6}" y="${s.y(q) + 4}" text-anchor="end" font-size="10" fill="#7b8494">${fmtNum(q, 0)}</text>`;
  }).join("");
  const xTicks = [0, 1 / 3, 2 / 3, 1].map((r) => {
    const t = s.t0 + (s.t1 - s.t0) * r;
    const d = new Date(t);
    const label = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    return `<text x="${s.x(t)}" y="${CHART.h - 6}" text-anchor="middle" font-size="10" fill="#7b8494">${label}</text>`;
  }).join("");

  const line = pts.map((p, i) =>
    `${i === 0 ? "M" : "L"}${s.x(new Date(p.time).getTime()).toFixed(1)},${s.y(p.qps).toFixed(1)}`).join(" ");

  svg.innerHTML = `
    ${yTicks}${xTicks}
    ${selRect(state.selections.base, "#f08c2d")}
    ${selRect(state.selections.target, "#22a06b")}
    <path d="${line}" fill="none" stroke="#6672f5" stroke-width="1.8"/>`;
}

// 드래그로 구간 선택 -> datetime 입력에 반영
function setupChartDrag() {
  const svg = $("#activity-chart");
  let dragStart = null;

  const pxOf = (ev) => {
    const rect = svg.getBoundingClientRect();
    return (ev.clientX - rect.left) / rect.width * CHART.w;
  };

  svg.addEventListener("pointerdown", (ev) => {
    if (!state.dragMode || state.activity.length < 2) return;
    dragStart = pxOf(ev);
    svg.setPointerCapture(ev.pointerId);
  });
  svg.addEventListener("pointermove", (ev) => {
    if (dragStart == null) return;
    const s = chartScales();
    const a = Math.min(dragStart, pxOf(ev)), b = Math.max(dragStart, pxOf(ev));
    state.selections[state.dragMode] = { from: new Date(s.invX(a)), to: new Date(s.invX(b)) };
    drawChart();
  });
  svg.addEventListener("pointerup", () => {
    if (dragStart == null) return;
    dragStart = null;
    const sel = state.selections[state.dragMode];
    if (!sel) return;
    const prefix = state.dragMode; // 'target' | 'base'
    $(`#${prefix}-from`).value = toLocalInput(sel.from);
    $(`#${prefix}-to`).value = toLocalInput(sel.to);
  });

  $("#mode-target").addEventListener("click", () => toggleDragMode("target"));
  $("#mode-base").addEventListener("click", () => toggleDragMode("base"));
}

function toggleDragMode(mode) {
  state.dragMode = state.dragMode === mode ? null : mode;
  $("#mode-target").classList.toggle("active", state.dragMode === "target");
  $("#mode-base").classList.toggle("active", state.dragMode === "base");
}

// ---------- Top Query: 단순 조회 ----------
async function runQuery() {
  state.compareMode = false;
  $("#compare-summary").hidden = true;
  closeDetail();
  const stats = await api(`/api/instances/${state.instance.id}/query-stats?limit=20`);
  const table = $("#top-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Load</th><th>Query</th><th class="num">Calls</th>
        <th class="num">Total(ms)</th><th class="num">Rows Examined</th></tr>`;
  table.querySelector("tbody").innerHTML = stats.map((q, idx) => `
    <tr data-idx="${idx}">
      <td class="num">${fmtNum(q.loadPct)}%</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
      <td class="num">${fmtNum(q.calls, 0)}</td>
      <td class="num">${fmtNum(q.totalTimeMs)}</td>
      <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
    </tr>`).join("");
  bindRowClicks(stats.map((q) => ({ queryId: q.queryId, queryText: q.queryText })));
}

// ---------- Top Query: 비교 조회 (증감 + NEW) ----------
async function runCompare() {
  const p = (id) => $(id).value;
  if (!p("#base-from") || !p("#base-to") || !p("#target-from") || !p("#target-to")) return;
  closeDetail();
  const qs = `baseFrom=${p("#base-from")}&baseTo=${p("#base-to")}&targetFrom=${p("#target-from")}&targetTo=${p("#target-to")}`;
  let result;
  try {
    result = await api(`/api/instances/${state.instance.id}/compare?${qs}`);
  } catch (e) {
    alert(`비교 실패: ${e.message}\n(구간 안에 스냅샷 배치가 2개 이상 필요합니다 — 수집 주기 1분)`);
    return;
  }
  state.compareMode = true;

  // 요약 스트립 — 표를 읽기 전에 "전반적으로 무엇이 변했는지"
  const sum = $("#compare-summary");
  sum.hidden = false;
  const pct = (v) => v == null ? "-" : `<span class="${v >= 0 ? "delta-up" : "delta-down"}">${v >= 0 ? "+" : ""}${fmtNum(v, 0)}%</span>`;
  sum.innerHTML = `
    <span class="summary-item">호출량 ${pct(result.totalCallsChangePct)}</span>
    <span class="summary-item">평균 레이턴시 ${pct(result.avgLatencyChangePct)}</span>
    <span class="summary-item">읽은 행수 ${pct(result.rowsExaminedChangePct)}</span>
    <span class="summary-item">신규 쿼리 <b>${result.newQueryCount}</b>개</span>
    <span class="summary-item muted">조회 ${esc($("#target-from").value.replace("T", " "))} ~ ${esc($("#target-to").value.slice(11))}
      / 비교 ${esc($("#base-from").value.replace("T", " "))} ~ ${esc($("#base-to").value.slice(11))}</span>`;

  // 표: target QPS 내림차순, 신규 쿼리 하이라이트
  const rows = [...result.queries].sort((a, b) => b.targetQps - a.targetQps);
  const table = $("#top-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Query</th><th class="num">QPS</th><th class="num">Latency(ms)</th><th class="num">Rows/call</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.map((q, idx) => `
    <tr data-idx="${idx}" class="${q.newQuery ? "new-query" : ""}">
      <td class="qtext" title="${esc(q.queryText)}">${q.newQuery ? '<span class="badge-new">NEW</span>' : ""}${esc(q.queryText)}</td>
      <td>${deltaCell(q.baseQps, q.targetQps, q.qpsChangePct)}</td>
      <td>${deltaCell(q.baseAvgMs, q.targetAvgMs, q.latencyChangePct)}</td>
      <td>${deltaCell(q.baseRowsPerCall, q.targetRowsPerCall, q.rowsPerCallChangePct, 0)}</td>
    </tr>`).join("");
  bindRowClicks(rows);
}

// ---------- 쿼리 상세 (EXPLAIN + AI) ----------
function bindRowClicks(rows) {
  $("#top-table").querySelectorAll("tbody tr").forEach((tr) => {
    tr.addEventListener("click", () => {
      document.querySelectorAll("#top-table tbody tr").forEach((r) => r.classList.remove("selected"));
      tr.classList.add("selected");
      openDetail(rows[tr.dataset.idx]);
    });
  });
}

function openDetail(query) {
  state.currentQuery = query;
  $("#query-detail").hidden = false;
  $("#detail-qid").textContent = `SQL ID: ${query.queryId}`;
  $("#detail-sql").value = query.queryText ?? "";
  $("#plan-section").hidden = true;
  $("#ai-section").hidden = true;
  $("#inquiry-section").hidden = true;
  $("#inquiry-note").value = "";
  $("#inquiry-result").innerHTML = "";
  // 쿼리를 새로 열면 직전 쿼리의 분석 결과는 첨부 대상이 아니다 — 비운다
  state.lastPlan = null;
  state.lastFindings = [];
  state.lastAi = null;
  $("#query-detail").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function closeDetail() {
  $("#query-detail").hidden = true;
  state.currentQuery = null;
}

async function runExplain() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-explain");
  btn.classList.add("loading");
  try {
    let data;
    $("#plan-section").hidden = false;
    try {
      data = await api(`/api/instances/${state.instance.id}/explain`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      $("#detail-plan").textContent = `실행 실패: ${e.message}`;
      $("#detail-findings").innerHTML = "";
      return;
    }
    $("#detail-plan").textContent = data.plan;
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("") ||
      '<div class="muted">규칙 기반 지적 없음 — 비효율 신호가 발견되지 않았습니다.</div>';
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
  } finally { btn.classList.remove("loading"); }
}

async function runAiAnalysis() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-ai");
  btn.classList.add("loading");
  $("#ai-section").hidden = false;
  $("#detail-ai").textContent = "분석 중... (실행계획 조회 후 AI 판정)";
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/ai-analysis`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) { $("#detail-ai").textContent = `실패: ${e.message}`; return; }
    // 실행계획 섹션도 함께 갱신 (같은 응답에 plan/findings 포함)
    $("#plan-section").hidden = false;
    $("#detail-plan").textContent = data.plan;
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("");
    $("#detail-ai").textContent = data.aiAnalysis ??
      "AI 분석 비활성화 상태입니다 (ANTHROPIC_API_KEY도 claude CLI도 없음) — 규칙 기반 지적까지만 표시합니다.";
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
    state.lastAi = data.aiAnalysis ?? null;
  } finally { btn.classList.remove("loading"); }
}

// 현재 상세 패널의 쿼리·실행계획·규칙 지적·AI 분석을 모아 DB팀에 문의(웹훅 push).
// 실행계획/AI를 안 돌렸어도 쿼리만으로 문의할 수 있게 plan/findings/ai는 있으면 첨부한다.
async function runInquiry() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-inquiry");
  btn.classList.add("loading");
  $("#inquiry-section").hidden = false;
  const result = $("#inquiry-result");
  result.innerHTML = '<div class="muted">전송 중...</div>';
  try {
    const body = {
      sql,
      plan: state.lastPlan,
      findings: state.lastFindings,
      aiAnalysis: state.lastAi,
      note: $("#inquiry-note").value.trim() || null,
    };
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/inquiry`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">문의 실패: ${esc(e.message)}</div>`;
      return;
    }
    result.innerHTML = data.sent
      ? '<div class="finding-item">DB팀에 전송되었습니다.</div>'
      : `<div class="finding-item">전송되지 않음 — ${esc(data.reason ?? "웹훅 미설정")}</div>`;
  } finally { btn.classList.remove("loading"); }
}

// ---------- Slow Query / Monitoring ----------
async function loadSlow() {
  const rows = await api(`/api/instances/${state.instance.id}/slow-queries?limit=20`);
  const table = $("#slow-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Captured</th><th class="num">Elapsed(ms)</th><th class="num">Rows</th><th>Query</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.length ? rows.map((q) => `
    <tr>
      <td class="num">${esc(q.capturedAt ?? "-")}</td>
      <td class="num">${fmtNum(q.elapsedMs)}</td>
      <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
    </tr>`).join("") : '<tr><td colspan="4" class="muted">슬로우 쿼리가 없습니다.</td></tr>';
}

// MCP 카드 — 도구 목록을 실제 /mcp 엔드포인트(tools/list)에서 받아와 그린다.
// 하드코딩하지 않는 이유: 이 목록이 곧 "MCP가 살아 있다"의 증거가 되기 때문.
async function loadMcpTools() {
  const box = $("#mcp-tools");
  try {
    const data = await api("/mcp", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
    });
    box.classList.remove("muted");
    box.innerHTML = data.result.tools.map((t) => `
      <div class="mcp-tool"><b>${esc(t.name)}</b><p>${esc(t.description)}</p></div>`).join("");
  } catch (e) {
    box.textContent = e.message.startsWith("403")
      ? "MCP 카드는 ADMIN 역할만 볼 수 있습니다 (서비스 토큰 노출 방지)"
      : `도구 목록 조회 실패: ${e.message}`;
  }
}

// MCP 등록 명령 — ADMIN이면 서비스 토큰을 받아 실제 명령을 완성한다 (A1)
async function loadMcpCommand() {
  try {
    const { token } = await api("/api/security/mcp-token");
    $("#mcp-cmd-http").textContent =
      `claude mcp add --transport http dbtower http://localhost:8080/mcp --header "Authorization: Bearer ${token}"`;
  } catch { /* VIEWER — 기본 안내 문구 유지 */ }
}

// 상단 사용자 표시 + 로그아웃
async function loadMe() {
  try {
    const me = await api("/api/me");
    state.role = me.role;
    $("#user-chip").innerHTML =
      `${esc(me.username)}<span class="role-badge">${esc(me.role)}</span>`;
  } catch { /* 401이면 api()가 로그인으로 보낸다 */ }
  $("#logout-btn")?.addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": csrfToken() } });
    location.href = "/login.html";
  });
}

function setupCopyButtons() {
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const text = $(`#${btn.dataset.copy}`).textContent.split("   (")[0].trim();
      try {
        await navigator.clipboard.writeText(text);
        const old = btn.textContent;
        btn.textContent = "복사됨";
        setTimeout(() => { btn.textContent = old; }, 1200);
      } catch { /* http 컨텍스트 등 클립보드 불가 환경 — 무시 */ }
    });
  });
}

async function loadReplication() {
  try {
    const r = await api(`/api/instances/${state.instance.id}/replication`);
    $("#replication-box").textContent =
      `role: ${r.role}\nlagSeconds: ${r.lagSeconds}\n${r.detail ?? ""}`;
  } catch (e) { $("#replication-box").textContent = `조회 실패: ${e.message}`; }
}

// Wait Events — 기종별 의미가 다르다(누적/순간 스냅샷/큐 게이지). 시간 정보가 없는 소스는
// totalMs=0으로 오므로 "-"로 표시해 "0ms 기다렸다"로 오독되지 않게 한다.
async function loadWaitEvents() {
  const table = $("#wait-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Category</th><th>Event</th><th class="num">Count</th><th class="num">Total(ms)</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/wait-events?limit=20`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((w) => `
      <tr>
        <td>${esc(w.category)}</td>
        <td class="qtext" title="${esc(w.event)}">${esc(w.event)}</td>
        <td class="num">${fmtNum(w.count, 0)}</td>
        <td class="num">${w.totalMs > 0 ? fmtNum(w.totalMs) : "-"}</td>
      </tr>`).join("") : '<tr><td colspan="4" class="muted">대기 이벤트가 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="4" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// 세션 / 블로킹 (B2) — "지금 누가 누구를 막고 있나". blockedByPid가 있으면 행을 강조한다.
// ADMIN이면 행마다 취소(force=false)/강제종료(force=true) 버튼을 붙인다. VIEWER면 버튼 없음.
async function loadSessions() {
  const table = $("#session-table");
  const isAdmin = state.role === "ADMIN";
  const cols = isAdmin ? 8 : 7;
  table.querySelector("thead").innerHTML = `
    <tr><th class="num">PID</th><th>User</th><th>State</th><th>Wait</th>
        <th class="num">BlockedBy</th><th class="num">Elapsed(ms)</th><th>Query</th>${isAdmin ? "<th>Action</th>" : ""}</tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/sessions?limit=50`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((s) => `
      <tr class="${s.blockedByPid != null ? "blocked-row" : ""}">
        <td class="num">${esc(s.pid)}</td>
        <td>${esc(s.user ?? "-")}</td>
        <td>${esc(s.state ?? "-")}</td>
        <td>${esc(s.waitEvent ?? "-")}</td>
        <td class="num">${s.blockedByPid != null ? `<span class="blocked-by">${esc(s.blockedByPid)}</span>` : "-"}</td>
        <td class="num">${fmtNum(s.elapsedMs)}</td>
        <td class="qtext" title="${esc(s.query)}">${esc(s.query ?? "-")}</td>
        ${isAdmin ? `<td class="session-actions">
          <button class="btn btn-small" data-kill="${esc(s.pid)}" data-force="false">취소</button>
          <button class="btn btn-small btn-danger" data-kill="${esc(s.pid)}" data-force="true">강제종료</button>
        </td>` : ""}
      </tr>`).join("") : `<tr><td colspan="${cols}" class="muted">활성 세션이 없습니다.</td></tr>`;
    if (isAdmin) wireKillButtons();
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="${cols}" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// kill은 confirm 없이 바로 POST한다(장애 시 빠른 처치가 목적) — 대신 버튼 자체가 ADMIN에게만 보인다.
// 성공하면 목록을 다시 불러 사라졌는지 확인시킨다.
function wireKillButtons() {
  document.querySelectorAll("#session-table [data-kill]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const pid = btn.dataset.kill;
      const force = btn.dataset.force === "true";
      btn.disabled = true;
      try {
        await api(`/api/instances/${state.instance.id}/sessions/${pid}/kill?force=${force}`, { method: "POST" });
        await loadSessions();
      } catch (e) {
        btn.disabled = false;
        alert(`세션 종료 실패: ${e.message}`);
      }
    });
  });
}

// ---------- 감사 로그 검색 (Specification 동적 필터) ----------
async function loadAudit() {
  const table = $("#audit-table");
  const qs = new URLSearchParams();
  const p = $("#audit-principal").value.trim();
  const a = $("#audit-action").value.trim();
  const o = $("#audit-outcome").value.trim();
  if (p) qs.set("principal", p);
  if (a) qs.set("action", a);
  if (o) qs.set("outcome", o);
  qs.set("limit", "50");
  try {
    const rows = await api(`/api/audit?${qs.toString()}`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((e) => `
      <tr>
        <td>${esc((e.occurredAt || "").replace("T", " ").slice(0, 19))}</td>
        <td>${esc(e.principal)}</td>
        <td>${esc(e.role ?? "-")}</td>
        <td class="qtext" title="${esc(e.action)}">${esc(e.action)}</td>
        <td class="num">${e.outcome}</td>
        <td class="num">${e.durationMs == null ? "-" : e.durationMs}</td>
      </tr>`).join("") : '<tr><td colspan="6" class="muted">조건에 맞는 기록이 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML = e.message.startsWith("403")
      ? '<tr><td colspan="6" class="muted">감사 로그는 ADMIN 역할만 볼 수 있습니다.</td></tr>'
      : `<tr><td colspan="6" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// ---------- 탭/프리셋/초기화 ----------
function setupTabs() {
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      ["top", "slow", "monitor"].forEach((name) => {
        $(`#tab-${name}`).hidden = name !== tab.dataset.tab;
      });
    });
  });
}

function setupPresets() {
  document.querySelectorAll(".preset").forEach((btn) => {
    btn.addEventListener("click", () => {
      const mins = Number(btn.dataset.mins);
      const now = new Date();
      $("#target-to").value = toLocalInput(now);
      $("#target-from").value = toLocalInput(new Date(now - mins * 60000));
      $("#base-to").value = toLocalInput(new Date(now - mins * 60000));
      $("#base-from").value = toLocalInput(new Date(now - 2 * mins * 60000));
    });
  });
}

document.addEventListener("DOMContentLoaded", async () => {
  // 세션 kill 버튼 노출 여부가 역할에 달려 있어, 인스턴스 로딩(→세션 표) 전에 역할을 먼저 확정한다
  await loadMe();
  loadMcpCommand();
  loadInstances();
  setupTabs();
  setupPresets();
  setupChartDrag();
  setupCopyButtons();
  loadMcpTools();
  $("#btn-query").addEventListener("click", runQuery);
  $("#btn-compare").addEventListener("click", runCompare);
  $("#btn-explain").addEventListener("click", runExplain);
  $("#btn-ai").addEventListener("click", runAiAnalysis);
  $("#btn-inquiry").addEventListener("click", runInquiry);
  $("#audit-search-btn").addEventListener("click", loadAudit);
  $("#audit-reset-btn").addEventListener("click", () => {
    ["audit-principal", "audit-action", "audit-outcome"].forEach((id) => { $(`#${id}`).value = ""; });
    loadAudit();
  });
});
