// DBTower 웹 콘솔 — 프레임워크 없는 정적 SPA.
// 백엔드가 본질인 프로젝트라 프론트는 의존성 0으로 얇게 유지한다 (java -jar 하나로 화면까지).
// 화면 구도 참고: 인스턴스 선택 -> 그래프 드래그로 구간 선택
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
    // 204 No Content(대화 삭제 등)에는 본문이 없다 — r.json()이 던지면 성공이 실패로 보인다
    return r.status === 204 ? null : r.json();
  });
};

const state = {
  instance: null,      // 선택된 인스턴스 {id, name, type, ...}
  instances: [],       // 등록된 인스턴스 전체 목록 (Schema Diff 드롭다운용)
  activity: [],        // [{time, qps, avgLatencyMs}]
  metricsCpu: [],      // [{time, value}] — Prometheus CPU% (드래그 차트 CPU 모드 + Metric 카드)
  chartMetric: "qps",  // 드래그 차트의 데이터: 'qps' | 'cpu'
  dragMode: null,      // 'target' | 'base'
  selections: {},      // {target: {from: Date, to: Date}, base: {...}}
  compareMode: false,  // 마지막 조회가 비교 조회였는지
  compareSeq: 0,       // 겹친 비교 요청 중 마지막 응답만 화면에 반영
  currentQuery: null,  // 상세 패널에 열린 쿼리
  lastPlan: null,      // 마지막 EXPLAIN 실행계획 (문의 첨부용)
  lastFindings: [],    // 마지막 규칙 기반 지적
  lastAi: null,        // 마지막 AI 분석
  role: null,          // 로그인 주체의 대표 역할(표시용)
  username: null,      // 로그인 주체의 이름(/api/me username) — 내 작업에만 취소 버튼을 붙이는 판정에 쓴다
  caps: new Set(),     // 로그인 주체의 능력(/api/me capabilities) — 버튼·메뉴는 역할 이름이 아니라 이것으로 가른다
};

// ---------- 유틸 ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
// 화면 표시만 가른다 — 인가는 서버(SecurityConfig)가 한다. 누를 수 없는 버튼을 보여주지 않으려는 것이다
const can = (cap) => state.caps.has(cap);
const ROLE_LABEL = { VIEWER: "관제", REQUESTER: "요청자", APPROVER: "승인자", OPERATOR: "운영자", ADMIN: "관리자" };

// AI 서술 출력에서 이모지만 제거(우리 규칙: 이모지 금지). →·✓ 같은 기술 기호는 보존.
const stripEmoji = (s) => String(s ?? "").replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{26FF}✨️‍]/gu, "");

// SQL 정규화 텍스트(digest)가 한 줄로 뭉쳐 오므로, SQL 문법처럼 절·컬럼을 줄바꿈해 읽기 좋게 만든다.
// 의존성 0 경량 포매터(완벽한 파서 아님) — 주요 절 앞 개행 + SELECT 최상위 컬럼 개행. 괄호 깊이로 함수 인자 콤마는 보존.
function formatSql(sql) {
  if (!sql || typeof sql !== "string") return sql || "";
  let s = sql.replace(/\s+/g, " ").trim();
  s = s.replace(/\s*\.\s*/g, ".");                       // `스키마` . `표` → `스키마`.`표`
  s = s.replace(/\(\s+/g, "(").replace(/\s+\)/g, ")");   // 괄호 안쪽 공백 제거
  s = s.replace(/\s*,\s*/g, ", ");
  const majors = ["LEFT OUTER JOIN", "RIGHT OUTER JOIN", "FULL OUTER JOIN", "INNER JOIN",
    "LEFT JOIN", "RIGHT JOIN", "CROSS JOIN", "JOIN", "FROM", "WHERE", "GROUP BY", "HAVING",
    "ORDER BY", "LIMIT", "OFFSET", "UNION ALL", "UNION", "SET", "VALUES"];
  majors.forEach((kw) => {
    const re = new RegExp("\\s+(" + kw.replace(/ /g, "\\s+") + ")\\s+", "gi");
    s = s.replace(re, (m, g) => "\n" + g.replace(/\s+/g, " ").toUpperCase() + " ");
  });
  const lines = s.split("\n");
  if (/^\s*select/i.test(lines[0])) {
    const kw = lines[0].match(/^\s*(SELECT(?:\s+DISTINCT)?)/i)[1].replace(/\s+/g, " ").toUpperCase();
    const cols = lines[0].replace(/^\s*SELECT(?:\s+DISTINCT)?\s+/i, "");
    let depth = 0, out = "", buf = "";
    for (const ch of cols) {
      if (ch === "(") depth++;
      else if (ch === ")") depth--;
      if (ch === "," && depth === 0) { out += buf.trim() + ",\n       "; buf = ""; }
      else buf += ch;
    }
    lines[0] = kw + " " + out + buf.trim();
  }
  return lines.join("\n");
}

// 실행계획을 읽기 좋게 — JSON 형식(MySQL EXPLAIN FORMAT=JSON 등)이면 들여쓰기, 텍스트 트리(PostgreSQL)·
// 기타 기종은 원문 그대로. 5기종 어떤 plan 형식이 와도 안전하게(파싱 실패 시 원문 유지).
function prettyPlan(plan) {
  if (!plan || typeof plan !== "string") return plan || "";
  const t = plan.trim();
  if (t.startsWith("{") || t.startsWith("[")) {
    try { return JSON.stringify(JSON.parse(t), null, 2); } catch { /* JSON 아님 — 원문 */ }
  }
  return plan;
}

// JSON 실행계획을 구문 강조(키·문자열·숫자·불리언/null 색 구분)해 코드블록에 색을 입힌다. esc 후 토큰만 span 래핑.
function highlightJson(pretty) {
  return esc(pretty).replace(
    /(&quot;(?:[^&]|&(?!quot;))*?&quot;)(\s*:)?|\b(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b|\b(true|false|null)\b/g,
    (m, str, colon, num, kw) => {
      if (str != null) return `<span class="${colon ? "j-key" : "j-str"}">${str}</span>${colon || ""}`;
      if (num != null) return `<span class="j-num">${num}</span>`;
      if (kw != null) return `<span class="j-kw">${kw}</span>`;
      return m;
    });
}

// 경량 SQL 구문 강조(의존성 0) — 키워드·함수·문자열·숫자·식별자·주석·연산자를 char 스캔으로 토큰화해 색을 입힌다.
const SQL_KEYWORDS = new Set(("SELECT FROM WHERE GROUP BY ORDER HAVING LIMIT OFFSET JOIN LEFT RIGHT INNER OUTER FULL "
  + "CROSS ON USING AND OR NOT NULL IS IN LIKE ILIKE BETWEEN AS DISTINCT UNION ALL EXCEPT INTERSECT INSERT INTO "
  + "VALUES UPDATE SET DELETE CREATE ALTER DROP TRUNCATE TABLE INDEX VIEW ASC DESC EXISTS CASE WHEN THEN ELSE END "
  + "CAST INTERVAL RETURNING WITH RECURSIVE OVER PARTITION FOR").split(/\s+/));
const SQL_FUNCS = new Set(("COUNT SUM AVG MIN MAX COALESCE NOW EXTRACT LOWER UPPER LENGTH ROUND ABS IFNULL ISNULL "
  + "NVL GREATEST LEAST DATE_SUB DATE_ADD CONCAT SUBSTRING").split(/\s+/));

function highlightSql(sql) {
  let out = "", i = 0;
  const n = (sql || "").length;
  const span = (cls, txt) => `<span class="${cls}">${esc(txt)}</span>`;
  while (i < n) {
    const ch = sql[i];
    if (ch === "-" && sql[i + 1] === "-") { let j = i; while (j < n && sql[j] !== "\n") j++; out += span("t-com", sql.slice(i, j)); i = j; continue; }
    if (ch === "/" && sql[i + 1] === "*") { let j = sql.indexOf("*/", i + 2); j = j < 0 ? n : j + 2; out += span("t-com", sql.slice(i, j)); i = j; continue; }
    if (ch === "'") { let j = i + 1; while (j < n) { if (sql[j] === "'" && sql[j + 1] === "'") { j += 2; continue; } if (sql[j] === "'") { j++; break; } j++; } out += span("t-str", sql.slice(i, j)); i = j; continue; }
    if (ch === "`" || ch === '"') { const q = ch; let j = i + 1; while (j < n && sql[j] !== q) j++; j++; out += span("t-id", sql.slice(i, j)); i = j; continue; }
    if (ch >= "0" && ch <= "9") { let j = i; while (j < n && /[0-9.]/.test(sql[j])) j++; out += span("t-num", sql.slice(i, j)); i = j; continue; }
    if (/[A-Za-z_]/.test(ch)) {
      let j = i; while (j < n && /[A-Za-z0-9_$]/.test(sql[j])) j++;
      const w = sql.slice(i, j), up = w.toUpperCase();
      if (SQL_KEYWORDS.has(up)) out += span("t-kw", w);
      else if (SQL_FUNCS.has(up) || sql[j] === "(") out += span("t-fn", w);
      else out += esc(w);
      i = j; continue;
    }
    if (/[=<>!+\-*/%,;().]/.test(ch)) { out += span("t-op", ch); i++; continue; }
    out += esc(ch); i++;
  }
  return out;
}

// 표·카드에 SQL을 그릴 때도 상세 편집기와 같은 토큰화를 쓴다. highlightSql이 토큰마다 esc를 거치므로
// API에서 받은 쿼리를 innerHTML에 넣어도 태그로 실행되지 않는다.
function queryTextHtml(sql) {
  return highlightSql(sql ?? "-");
}

// 오버레이 갱신 — 투명 textarea 뒤의 하이라이트 레이어를 현재 값으로 다시 그린다(마지막 줄이 보이게 개행 하나 덧붙임).
function updateSqlHl() {
  const ta = $("#detail-sql"), hl = $("#detail-sql-hl");
  if (!hl) return;
  hl.innerHTML = highlightSql(ta.value) + "\n";
  hl.scrollTop = ta.scrollTop;
  hl.scrollLeft = ta.scrollLeft;
}

// 실행계획을 안전한 HTML로 — JSON이면 하이라이트, 텍스트 트리·기타는 esc. (문자열 조립·엘리먼트 주입 공용)
function planHtml(plan) {
  const pp = prettyPlan(plan);
  const t = (pp || "").trim();
  return (t.startsWith("{") || t.startsWith("[")) ? highlightJson(pp) : esc(pp);
}

// 실행계획을 대상 엘리먼트에 렌더 — planHtml을 innerHTML로.
function renderPlanInto(el, plan) {
  el.innerHTML = planHtml(plan);
}

// 표 형태 실행계획(MySQL 클래식 EXPLAIN 등) — 레퍼런스처럼 id/type/key/rows/Extra 컬럼 표로. 컬럼은 응답 그대로.
function renderPlanTable(rows) {
  const cols = Object.keys(rows[0]);
  const head = cols.map((c) => `<th>${esc(c)}</th>`).join("");
  const body = rows.map((r) => `<tr>${cols.map((c) => {
    const v = r[c];
    return `<td>${v == null || v === "" ? '<span class="muted">—</span>' : esc(String(v))}</td>`;
  }).join("")}</tr>`).join("");
  return `<table class="plan-table"><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

// 실행계획 섹션 채우기 — 표(planTable)가 오면 표로, 아니면 색상 JSON/트리로.
function fillPlan(el, data) {
  if (data.planTable && data.planTable.length) el.innerHTML = renderPlanTable(data.planTable);
  else renderPlanInto(el, data.plan);
}

// datetime-local 입력값(로컬 시각)과 LocalDateTime(ISO) 사이 변환
const toLocalInput = (date) => {
  const p = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())}T${p(date.getHours())}:${p(date.getMinutes())}`;
};
// API로 보내는 시각 — 서버 JVM은 UTC 고정(DbtowerApplication)이라, 브라우저 벽시계(예: KST)를
// 그대로 보내면 9시간 스큐로 빈 구간을 조회한다. 화면 입력은 로컬로 보여주되 호출 직전 UTC로 변환한다.
const toApiTime = (v) => new Date(v).toISOString();
// API가 주는 시각 — 서버 LocalDateTime은 UTC 벽시계인데 오프셋 표기가 없어, 그대로 new Date()에 넣으면
// 브라우저 로컬로 오파싱된다. Z를 붙여 진짜 instant로 만들고, 표시는 브라우저 로컬로 통일한다.
const parseApiTime = (s) => new Date(/Z$|[+-]\d\d:?\d\d$/.test(s) ? s : s + "Z");
const fmtNum = (v, digits = 2) => {
  const n = Number(v);
  return v == null || !Number.isFinite(n) ? "-" : n.toLocaleString("ko-KR", { maximumFractionDigits: digits });
};

// 바이트를 사람이 읽는 단위로 (파티션 크기 등). 1024 진법, 소수 한 자리.
const fmtBytes = (v) => {
  if (v == null) return "-";
  let n = Number(v);
  if (!Number.isFinite(n) || n < 0) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let u = 0;
  while (n >= 1024 && u < units.length - 1) { n /= 1024; u += 1; }
  return `${u === 0 ? n : n.toFixed(1)} ${units[u]}`;
};

// 증감 셀: "target값 (▲ diff)" 표기. changePct가 null(base 0)이면 화살표 생략
function deltaCell(base, target, changePct, digits = 2) {
  const t = fmtNum(target, digits);
  if (changePct == null) return `<span class="num">${t}</span>`;
  const diff = target - base;
  const cls = diff >= 0 ? "delta-up" : "delta-down";
  const arrow = diff >= 0 ? "▲" : "▼";
  return `<span class="num">${t} <span class="${cls}">(${arrow} ${fmtNum(Math.abs(diff), digits)})</span></span>`;
}

// ---------- 인스턴스 (검색·필터 구동) ----------
// 수백~수천 대를 상정 — 전부 렌더하지 않는다. 목록은 메모리에 두고, 검색/필터가 있을 때만 매칭분을 그린다.
const INSTANCE_RENDER_CAP = 60; // 한 번에 그리는 상한(렉 방지)

async function loadInstances() {
  const list = await api("/api/instances");
  state.instances = list;
  populateSchemaSelects(list);
  const serverCount = {};
  list.forEach((i) => { const k = `${i.host.toLowerCase()}:${i.port}`; serverCount[k] = (serverCount[k] || 0) + 1; });
  state.serverCount = serverCount;
  populateInstanceFilterOptions(list);
  renderInstanceMatches();
  renderInstanceOnboarding();
  handleInstanceDeepLink(list);
}

/**
 * 등록된 인스턴스가 한 대도 없을 때의 한 줄 (B6).
 *
 * 함대 카드 둘(헬스 스코어·백업 신선도)은 빈 채로 같은 문장을 두 번 말하게 되므로 접고 여기서 한 번만 말한다.
 * 역할에 따라 할 수 있는 일이 다르다 — 관제 역할에게 "등록하세요"라고 하면 할 수 없는 일을 권하는 것이다.
 * 등록 화면은 콘솔에 없다(만들지 않는다) — 있지도 않은 화면으로 가는 버튼 대신 실제 입구를 적는다.
 */
function renderInstanceOnboarding() {
  const box = $("#inst-onboarding");
  const fleet = $("#fleet-row");
  const none = state.instances.length === 0;
  box.hidden = !none;
  fleet.hidden = none;
  // 대화 칸의 안내도 같은 사실 위에 선다 — 목록이 늦게 도착하면 "왼쪽에서 고르세요"로 굳는다(B6)
  if (!state.instance) renderChat({});
  if (!none) return;
  box.innerHTML = state.role === "ADMIN"
    ? `<p class="empty-onboarding-line">등록된 인스턴스가 없습니다. 콘솔에는 등록 화면이 없고,
       <code>POST /api/instances</code>(또는 IaC의 멱등 upsert)로 등록하면 여기에 나타납니다.</p>`
    : `<p class="empty-onboarding-line">등록된 인스턴스가 없습니다. 등록은 ADMIN이 하므로 관리자에게 요청하세요.</p>`;
}

/**
 * 집계 카드가 비었을 때의 문장 — "등록된 인스턴스가 없습니다"라고 쓰면 화면이 거짓말을 한다.
 * 이 카드들은 주기 집계 스냅샷을 그대로 보여주므로, 바로 아래 카드에 인스턴스가 버젓이 있는데도 그 문장이 떴다(B6).
 * 등록 수(라이브)와 집계에 든 수(스냅샷)를 갈라서 말한다.
 */
function emptyAggregateNote() {
  return state.instances.length
    ? `이 집계에 든 인스턴스가 없습니다 — 등록 ${state.instances.length}대, 다음 집계 뒤 다시 봅니다.`
    : "등록된 인스턴스가 없습니다.";
}

// 엔진 아이콘 — 공식 브랜드 로고(devicon SVG)를 스프라이트 심볼로 1회 정의하고 <use>로 참조(DOM 폭증 방지).
// 5기종 외(미지원 타입)면 빈 문자열. 스프라이트는 index.html의 .db-sprite에 박혀 있다.
const ENGINE_TYPES = new Set(["MYSQL", "POSTGRESQL", "MONGODB", "ORACLE", "MSSQL"]);
function engineIcon(type) {
  if (!ENGINE_TYPES.has(type)) return "";
  return `<svg class="db-icon" viewBox="0 0 128 128" width="16" height="16" aria-hidden="true"><use href="#dbicon-${type}"></use></svg>`;
}

// 기종 아이콘 + 배지 — 목록·헬스·백업 어디서든 같은 표기로(레퍼런스처럼 아이콘으로 기종 구분)
function typeBadge(type) {
  return `${engineIcon(type)}<span class="type-badge type-${esc(type)}">${esc(type)}</span>`;
}

// 카드 HTML — 이름 행은 항상, 상세(host·태그)는 선택 시에만 CSS로 펼친다(선택한 DB만 자세히).
function instanceCardHtml(i) {
  const serverKey = `${i.host.toLowerCase()}:${i.port}`;
  const cnt = state.serverCount[serverKey] || 1;
  const sharedNames = cnt > 1
    ? state.instances.filter((o) => o.id !== i.id && `${o.host.toLowerCase()}:${o.port}` === serverKey).map((o) => o.name) : [];
  const selected = state.instance && state.instance.id === i.id ? " selected" : "";
  return `
    <div class="instance-card${selected}" data-id="${i.id}" data-name="${esc(i.name.toLowerCase())}"
         data-host="${esc(i.host.toLowerCase())}" data-type="${esc(i.type)}" data-team="${esc(i.teamLabel || "")}"
         data-env="${esc(i.environment || "")}" data-region="${esc(i.region || "")}" data-cluster="${esc(i.cluster || "")}">
      <div class="instance-name">
        ${engineIcon(i.type)}
        <span class="inst-name-text">${esc(i.name)}</span>
        <span class="repl-role" id="role-${i.id}"></span>
        <span class="health-dot" id="health-${i.id}"></span>
      </div>
      <div class="instance-detail">
        <div class="inst-field"><span class="k">기종</span><span class="v"><span class="type-badge type-${esc(i.type)}">${esc(i.type)}</span></span></div>
        <div class="inst-field"><span class="k">호스트</span><span class="v">${esc(i.host)}:${i.port}</span></div>
        <div class="inst-field"><span class="k">DB</span><span class="v">${esc(i.dbName)}${sharedNames.length ? ` <span class="server-shared-badge" title="같은 서버(${esc(serverKey)})에 등록된 다른 인스턴스: ${esc(sharedNames.join(", "))} — 서버 전역 경보(복제·세션·데드락)는 그룹당 1회">서버 공유 ×${cnt}</span>` : ""}</span></div>
        <div class="inst-field"><span class="k">응답</span><span class="v" id="ping-${i.id}">—</span></div>
        <div class="inst-field"><span class="k">버전</span><span class="v ver" id="ver-${i.id}" title="">—</span></div>
        <div class="inst-field"><span class="k">수집</span><span class="v"><button class="collect-toggle ${i.collectionEnabled ? "" : "isolated"}" data-id="${i.id}"
          title="수집 격리 토글 — 끄면 스냅샷 수집·운영 경보에서 이 인스턴스를 뺀다(등록은 유지)">${i.collectionEnabled ? "수집중" : "격리됨"}</button></span></div>
        ${i.environment || i.region || i.cluster || i.teamLabel || i.consoleUrl || i.appSchema ? `<div class="instance-meta">
          ${i.appSchema ? `<span class="tag-badge" title="앱 스키마 — 모니터 계정이 딕셔너리·콘솔에서 볼 스키마(Oracle)">스키마 ${esc(i.appSchema)}</span>` : ""}
          ${i.environment ? `<span class="tag-badge tag-env" title="환경">${esc(i.environment)}</span>` : ""}
          ${i.region ? `<span class="tag-badge tag-region" title="리전">${esc(i.region)}</span>` : ""}
          ${i.cluster ? `<span class="tag-badge tag-cluster" title="클러스터">${esc(i.cluster)}</span>` : ""}
          ${i.teamLabel ? `<span class="team-badge" title="담당 팀/Slack">${esc(i.teamLabel)}</span>` : ""}
          ${i.consoleUrl && /^https?:\/\//.test(i.consoleUrl) ? `<a class="console-link" href="${esc(i.consoleUrl)}" target="_blank" rel="noopener" title="콘솔 딥링크(PI·Grafana 등)">콘솔 ↗</a>` : ""}
        </div>` : ""}
      </div>
    </div>`;
}

// 현재 검색·필터에 걸리는 인스턴스 — 아무 조건 없으면 빈 배열(초기 빈 리스트)
function matchingInstances() {
  const q = $("#inst-search").value.trim().toLowerCase();
  const eng = $("#inst-engine").value, env = $("#inst-env").value, region = $("#inst-region").value,
        cluster = $("#inst-cluster").value, team = $("#inst-team").value;
  const anyFilter = !!(q || eng || env || region || cluster || team);
  if (!anyFilter) return { anyFilter: false, matches: [] };
  const matches = state.instances.filter((i) =>
    (!q || i.name.toLowerCase().includes(q) || i.host.toLowerCase().includes(q))
    && (!eng || i.type === eng) && (!env || (i.environment || "") === env)
    && (!region || (i.region || "") === region) && (!cluster || (i.cluster || "") === cluster)
    && (!team || (i.teamLabel || "") === team));
  return { anyFilter: true, matches };
}

// 매칭분만 렌더 — 선택된 인스턴스는 조건과 무관하게 맨 위 유지. 렌더된 카드에만 헬스·역할·이벤트를 붙인다.
function renderInstanceMatches() {
  const box = $("#instance-list");
  const total = state.instances.length;
  const { anyFilter, matches } = matchingInstances();
  let shown = matches;
  if (state.instance && !matches.some((m) => m.id === state.instance.id)) {
    const sel = state.instances.find((i) => i.id === state.instance.id);
    if (sel) shown = [sel, ...matches];
  }
  $("#inst-count").textContent = anyFilter ? `${matches.length}/${total}` : `${total}대`;
  if (!shown.length) {
    // 등록된 인스턴스가 0대면 "검색하거나 필터를 고르세요"가 거짓말이 된다(찾을 것이 없다). 안내는 작업면 한 곳에만 둔다
    box.innerHTML = total === 0 ? ""
      : anyFilter
        ? '<div class="inst-empty muted">일치하는 인스턴스가 없습니다.</div>'
        : `<div class="inst-empty muted">위에서 검색하거나 필터를 선택하면 여기 표시됩니다.</div>`;
    return;
  }
  const capped = shown.slice(0, INSTANCE_RENDER_CAP);
  box.innerHTML = capped.map(instanceCardHtml).join("")
    + (shown.length > capped.length ? `<div class="inst-more muted">…${shown.length - capped.length}대 더 — 검색·필터로 좁히세요.</div>` : "");
  bindInstanceCards();
  loadInstanceMeta(capped);
}

function bindInstanceCards() {
  const box = $("#instance-list");
  box.querySelectorAll(".instance-card").forEach((card) => {
    card.addEventListener("click", () => selectInstance(state.instances.find((i) => i.id == card.dataset.id), card));
  });
  // 버전은 기본 한 줄(말줄임) — 클릭하면 전체 펼침/접힘 토글(사이드바 공간 활용)
  box.querySelectorAll(".v.ver").forEach((el) => {
    el.addEventListener("click", (e) => { e.stopPropagation(); el.classList.toggle("ver-full"); });
  });
  box.querySelectorAll(".collect-toggle").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const inst = state.instances.find((i) => i.id == btn.dataset.id);
      try {
        await api(`/api/instances/${inst.id}/collection`, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled: !inst.collectionEnabled }) });
        inst.collectionEnabled = !inst.collectionEnabled;
        renderInstanceMatches();
      } catch (err) {
        // 경고창 대신 목록 안에 적는다 — 어느 인스턴스에서 실패했는지 맥락이 남는다(162절)
        setInstanceNotice(`수집 토글 실패 — ${inst.name}: ${err.message}`);
      }
    });
  });
}

// 버전 문자열 축약 — MSSQL 등은 개행·빌드시각·저작권까지 붙어 길다. 첫 줄의 의미 있는 머리만, 전체는 title로.
function shortVersion(v) {
  if (!v) return "—";
  const head = String(v).split(/[\n\r]/)[0].replace(/\s+/g, " ").trim();
  return head.length > 60 ? head.slice(0, 60).trim() + "…" : head;
}

// 렌더된 카드에만 헬스(핑·버전)·복제 역할을 비동기로 채운다 — 화면에 보이는 만큼만 조회(수천 대 확장).
function loadInstanceMeta(rendered) {
  rendered.forEach(async (i) => {
    try {
      const h = await api(`/api/instances/${i.id}/health`);
      const dot = $(`#health-${i.id}`); if (dot) dot.classList.add(h.up ? "up" : "down");
      const ping = $(`#ping-${i.id}`), ver = $(`#ver-${i.id}`);
      // 버전은 전체를 textContent에 담고 축약은 CSS(한 줄 말줄임)에 맡긴다 — 클릭하면 전체가 그대로 펼쳐지게
      if (h.up) { if (ping) ping.textContent = `${h.pingMillis}ms`; if (ver) { ver.textContent = h.version || "—"; ver.title = h.version || ""; } }
      else if (ping) ping.textContent = h.message;
    } catch { const dot = $(`#health-${i.id}`); if (dot) dot.classList.add("down"); }
    try {
      const r = await api(`/api/instances/${i.id}/replication`);
      const role = $(`#role-${i.id}`);
      if (role && r && r.role && r.role !== "UNSUPPORTED" && r.role !== "NONE") {
        role.textContent = r.role;
        role.classList.add(/PRIMARY|SOURCE|MASTER/i.test(r.role) ? "role-primary" : "role-replica");
      }
    } catch { /* 역할 미확인 */ }
  });
}

function populateInstanceFilterOptions(list) {
  const opts = (id, placeholder, values) => {
    const sel = $(`#${id}`);
    const distinct = [...new Set(values.filter((v) => v != null && v !== ""))].sort();
    // 값이 없거나 하나뿐이면 고를 것이 없다 — 늘 "전체"만 보이는 필터는 동작하지 않는 것처럼 읽힌다(사용자 지적).
    // 서로 다른 값이 둘 이상일 때만 보인다(같은 값뿐이면 "무엇인지"는 인스턴스 카드가 이미 말한다)
    const show = distinct.length >= 2;
    sel.innerHTML = `<option value="">${placeholder}</option>`
      + distinct.map((v) => `<option>${esc(v)}</option>`).join("");
    if (!show && sel.value) sel.value = "";   // 감출 거면 걸려 있던 조건도 푼다
    sel.hidden = !show;
    // 커스텀 드롭다운(enhanceSelect)은 select를 .cs로 감싸므로 감추는 대상이 둘이다
    const wrap = sel.closest(".cs");
    if (wrap) wrap.hidden = !show;
    sel._csSync?.(); // 커스텀 드롭다운 버튼 텍스트 동기화
    return show;
  };
  const shown = [
    opts("inst-engine", "기종 전체", list.map((i) => i.type)),
    opts("inst-env", "환경 전체", list.map((i) => i.environment)),
    opts("inst-region", "리전 전체", list.map((i) => i.region)),
    opts("inst-cluster", "클러스터 전체", list.map((i) => i.cluster)),
    opts("inst-team", "팀 전체", list.map((i) => i.teamLabel)),
  ];
  // 다 감추면 필터 줄 자체를 감춘다 — 검색 칸은 남는다
  const row = $(".instance-filter-row");
  if (row) row.hidden = !shown.some(Boolean);
}

// 커스텀 호버 툴팁 — 네이티브 title은 느리고 스타일이 안 먹어 못생겼다. title을 전역에서 가로채
// data-tip으로 옮기고(네이티브 억제) 스타일 툴팁으로 보여준다. 모든 title[data-tip] 요소에 자동 적용.
function setupTooltip() {
  const tip = document.createElement("div");
  tip.className = "tooltip"; tip.hidden = true;
  document.body.appendChild(tip);
  let cur = null;
  const place = (el) => {
    const r = el.getBoundingClientRect(), t = tip.getBoundingClientRect();
    let top = r.top - t.height - 8;
    const below = top < 4;
    if (below) top = r.bottom + 8;
    let left = Math.max(6, Math.min(r.left + r.width / 2 - t.width / 2, window.innerWidth - t.width - 6));
    tip.style.top = `${top}px`; tip.style.left = `${left}px`;
    tip.classList.toggle("below", below);
  };
  const show = (el) => {
    // 네이티브 title이 남아 있으면 data-tip으로 옮겨 이중 툴팁을 막는다
    const native = el.getAttribute("title");
    if (native != null && native !== "") { el.dataset.tip = native; el.removeAttribute("title"); }
    const text = el.dataset.tip;
    if (!text) return;
    cur = el; tip.textContent = text; tip.hidden = false; place(el);
  };
  const hide = () => { if (cur) { tip.hidden = true; cur = null; } };
  document.addEventListener("mouseover", (e) => {
    const el = e.target.closest("[title],[data-tip]");
    if (el && el !== cur) show(el);
  });
  document.addEventListener("mouseout", (e) => {
    if (cur && !(e.relatedTarget && cur.contains(e.relatedTarget))) hide();
  });
  // 스크롤·리사이즈 시 위치가 어긋나면 그냥 숨긴다(따라다니게 하지 않음 — 단순·확실)
  window.addEventListener("scroll", hide, true);
  window.addEventListener("resize", hide);
}

// 표의 SQL 툴팁 (B3) — 셀은 말줄임이라 전체 문장이 안 보이는데, 네이티브 title은 강조 없는 한 줄이라
// "SELECT가 어디부터 어디까지인가"가 읽히지 않는다(사용자 지적). 문서에 하나만 두고 재사용한다 —
// 여러 개가 겹쳐 뜨는 것을 구조적으로 막고, 한 번에 하나만 뜬다.
const SQL_TIP_DELAY_MS = 300;
function setupSqlTip() {
  const tip = document.createElement("div");
  tip.id = "sql-tip"; tip.className = "sql-tip"; tip.hidden = true;
  tip.setAttribute("role", "tooltip");
  document.body.appendChild(tip);
  let cell = null, timer = null;

  const hide = () => {
    clearTimeout(timer); timer = null;
    if (!tip.hidden) { tip.hidden = true; tip.innerHTML = ""; }
    cell = null;
  };
  const place = (el) => {
    const r = el.getBoundingClientRect(), t = tip.getBoundingClientRect();
    let top = r.bottom + 8;
    if (top + t.height > window.innerHeight - 8) top = Math.max(8, r.top - t.height - 8);
    tip.style.top = `${top}px`;
    tip.style.left = `${Math.max(8, Math.min(r.left, window.innerWidth - t.width - 8))}px`;
  };
  const show = (el) => {
    const sql = el.getAttribute("data-sql-tip");
    if (!sql) return;
    tip.innerHTML = highlightSql(formatSql(sql));
    tip.hidden = false;
    place(el);
  };
  document.addEventListener("mouseover", (e) => {
    if (tip.contains(e.target)) return;          // 툴팁 위(스크롤바 포함)에 있으면 유지 — 안에서 스크롤할 수 있어야 한다
    const el = e.target.closest?.("[data-sql-tip]");
    if (!el) { hide(); return; }
    if (el === cell) return;
    clearTimeout(timer);
    cell = el;
    timer = setTimeout(() => show(el), SQL_TIP_DELAY_MS);
  });
  // 키보드 초점으로도 뜬다 — 마우스만 있는 툴팁은 키보드 사용자에게 없는 정보다(셀에 tabindex를 준 이유)
  document.addEventListener("focusin", (e) => {
    const el = e.target.closest?.("[data-sql-tip]");
    if (el) { clearTimeout(timer); cell = el; show(el); }
  });
  document.addEventListener("focusout", (e) => {
    if (e.target.closest?.("[data-sql-tip]")) hide();
  });
  // 스크롤·리사이즈로 자리가 어긋나면 숨긴다. 툴팁 안 스크롤은 툴팁의 것이라 닫지 않는다
  window.addEventListener("scroll", (e) => { if (!tip.contains(e.target)) hide(); }, true);
  window.addEventListener("resize", hide);
  document.addEventListener("keydown", (e) => { if (e.key === "Escape") hide(); });
  tip.addEventListener("mouseleave", hide);
}

// 검색·필터 이벤트 → 재렌더(입력·선택할 때만 매칭분을 그린다). 앱 로딩 시 한 번 연결.
function setupInstanceFilter() {
  ["inst-search", "inst-engine", "inst-env", "inst-region", "inst-cluster", "inst-team"].forEach((id) => {
    const el = $(`#${id}`);
    el.addEventListener(id === "inst-search" ? "input" : "change", renderInstanceMatches);
  });
  // 네이티브 select는 OS 피커가 커지고 스타일이 안 먹어 커스텀 드롭다운으로 감싼다(디자인 일치, 라이브러리 0)
  ["inst-engine", "inst-env", "inst-region", "inst-cluster", "inst-team"].forEach((id) => enhanceSelect($(`#${id}`)));
}

// 네이티브 select를 값의 원천으로 두고, 시각은 커스텀 버튼+패널로. 옵션 변경/선택은 그대로 change로 흐른다.
function enhanceSelect(sel) {
  if (sel.dataset.csDone) return;
  sel.dataset.csDone = "1";
  sel.classList.add("cs-native");
  const wrap = document.createElement("span");
  wrap.className = "cs";
  sel.parentNode.insertBefore(wrap, sel);
  wrap.appendChild(sel);
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "cs-btn";
  wrap.appendChild(btn);
  // 기종 옵션이면 브랜드 아이콘을 붙인다. 항목에 data-icon이 있으면 그것을 쓴다 —
  // 워크벤치 인스턴스처럼 항목 글자가 기종 이름이 아닌 목록도 같은 드롭다운을 쓴다(B5)
  const label = (o) => engineIcon(o.dataset?.icon || o.text) + `<span>${esc(o.text)}</span>`;
  const sync = () => {
    const o = sel.options[sel.selectedIndex];
    btn.innerHTML = o ? label(o) : "";
  };
  sync();
  sel._csSync = sync;
  let panel = null;
  const close = () => { if (panel) { panel.remove(); panel = null; btn.classList.remove("open"); document.removeEventListener("click", onDoc, true); } };
  const onDoc = (e) => { if (!wrap.contains(e.target)) close(); };
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    if (panel) { close(); return; }
    panel = document.createElement("div");
    panel.className = "cs-panel";
    [...sel.options].forEach((o, i) => {
      const it = document.createElement("div");
      it.className = "cs-opt" + (i === sel.selectedIndex ? " sel" : "");
      it.innerHTML = label(o);
      it.addEventListener("click", () => {
        sel.selectedIndex = i; sync();
        sel.dispatchEvent(new Event("change"));
        close();
      });
      panel.appendChild(it);
    });
    wrap.appendChild(panel);
    btn.classList.add("open");
    document.addEventListener("click", onDoc, true);
  });
}

function handleInstanceDeepLink(list) {
  const params = new URLSearchParams(location.search);
  // 워크벤치 모드로 열렸으면 관제 화면의 대상 조회(로더 18개)를 돌리지 않는다 — 관제로 돌아올 때 그 인스턴스를 연다(setMode)
  if (params.get("mode") === "workbench") return;
  const target = params.get("instance") ? list.find((i) => String(i.id) === params.get("instance")) : null;
  const deepJob = params.get("aiop");
  if (!target) {
    // 월간 점검 요약(MonthlyReportJob)은 인스턴스 없이 함대 전체를 가리킨다 — 전에는 처리하는 곳이 없어 빈 첫 화면만 열렸다(148절 감사)
    if (params.get("view") === "monthly") $("#score-summary")?.scrollIntoView({ block: "start" });
    // 결과 알림의 딥링크는 대상이 없는 작업도 가리킨다(정기 리포트는 범위 전체를 덮는다) — 그래서 링크에 instance를
    // 싣지 않고, 화면이 대상 없는 주소를 처리한다. 대상이 지워진 뒤 눌린 링크도 여기로 온다(170절 9번)
    if (deepJob) openDeepLinkJob(deepJob);
    return;
  }
  state.instance = target;
  renderInstanceMatches();
  const ready = selectInstance(target, $(`#instance-list .instance-card[data-id="${target.id}"]`));
  const deepQ = params.get("diagnose"), deepView = params.get("view"), compareAt = params.get("compareAt");
  // 워크벤치에서 변경을 실행한 운영자가 넘어오는 입구 — 실행 시각 앞 30분(기준)과 뒤 30분(대상)을 시점 비교로 바로 연다.
  // 기본 구간 설정과 첫 조회가 끝난 뒤에 덮어써야 selectInstance의 기본값에 지워지지 않는다
  if (compareAt && !Number.isNaN(parseApiTime(compareAt).getTime())) {
    ready.then(() => {
      const at = parseApiTime(compareAt), span = 30 * 60000;
      $("#base-from").value = toLocalInput(new Date(at.getTime() - span));
      $("#base-to").value = toLocalInput(at);
      $("#target-from").value = toLocalInput(at);
      $("#target-to").value = toLocalInput(new Date(Math.min(at.getTime() + span, Date.now())));
      runCompare();
    });
  }
  // Slack 결과·경보가 건 링크(?aiop=작업id)로 들어오면 그 작업을 연다 — 링크가 JSON API를 가리키면 사람이 읽을 화면이 없다
  if (deepJob) ready.then(() => openDeepLinkJob(deepJob));
  // 진단 입력은 늘 보이는 AI 칸에 있다(149절) — 모니터링 탭을 열 필요 없이 질문을 채운다
  if (deepQ) { const input = $("#diagnose-question"); input.value = deepQ; autoGrowChatInput(); syncChatComposer(); input.scrollIntoView({ block: "center" }); input.focus(); }
  if (deepView === "config-drift") { document.querySelector('.tab[data-tab="monitor"]').click(); showMonGroup("gov"); loadConfigDrift(); $("#config-drift-result").scrollIntoView({ block: "center" }); }
  if (deepView === "review") { document.querySelector('.tab[data-tab="monitor"]').click(); showMonGroup("gov"); loadReviews(); $(".review-gate-card").scrollIntoView({ block: "center" }); }
  // 인시던트 리포트 웹훅 카드(IncidentController)와 월간 리포트의 입구 — 링크는 있었는데 처리하는 곳이 없었다(148절 감사)
  if (deepView === "incident") { document.querySelector('.tab[data-tab="monitor"]').click(); showMonGroup("backup"); $("#incident-result").scrollIntoView({ block: "center" }); }
  if (deepView === "monthly") { document.querySelector('.tab[data-tab="monitor"]').click(); showMonGroup("backup"); $("#monthly-result").scrollIntoView({ block: "center" }); }
}

// 결과 알림이 건 ?aiop=작업id 딥링크로 그 작업을 펼친다. 카드가 Monitoring 탭의 진단 그룹 안에 있어 탭까지 옮겨야
// 화면에 뜬다(그룹만 바꾸면 탭이 Top Query에 머문다). 다른 대상의 작업도 보여야 그 작업이 어디서나 열린다
function openDeepLinkJob(jobId) {
  // 카드는 #result-panel 안에 있고 그 패널은 대상을 고르기 전에는 hidden이다 — 대상 없는 작업(정기 리포트)의
  // 알림 링크도 이 카드를 보여줘야 하므로 여기서 연다. 시간대 패널(#time-panel)은 대상 조회용이라 열지 않는다
  $("#result-panel").hidden = false;
  document.querySelector('.tab[data-tab="monitor"]').click();
  showMonGroup("diag");
  $("#aiops-all-instances").checked = true;
  loadAiOperations().then(() => openAiOperation(jobId));
  document.querySelector(".aiops-card")?.scrollIntoView({ block: "start" });
}

async function selectInstance(instance, card) {
  state.instance = instance;
  renderInstanceMatches(); // 선택 반영 — 선택 카드를 맨 위 유지·상세 펼침·하이라이트
  renderChat({ follow: true }); // AI 칸은 잠시 그대로 — 아래 로더가 서버에서 그 인스턴스 대화를 다시 읽어 그린다
  $("#time-panel").hidden = false;
  $("#result-panel").hidden = false;

  // 기본 구간: 조회 = 최근 30분, 비교 = 그 직전 30분
  const now = new Date();
  $("#target-to").value = toLocalInput(now);
  $("#target-from").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-to").value = toLocalInput(new Date(now - 30 * 60000));
  $("#base-from").value = toLocalInput(new Date(now - 60 * 60000));
  state.selections = {};

  // 실시간이 켜진 채 대상을 바꾸면 새 대상으로 갈아탄다 — 추이는 대상이 다르면 이어 그릴 수 없어 비운다
  live.history = [];
  drawLiveSpark();
  syncLive();
  // 로더 하나가 실패해도 이 약속은 깨지지 않는다. 딥링크(?aiop=·compareAt)가 이 약속에 .then으로 걸려 있어,
  // 한 로더의 502가 Promise.all을 reject시키면 화면은 아무 일도 하지 않은 채 조용히 끝난다(170절 9번).
  // 실패는 각 로더가 자기 카드에 적는다 — 여기서는 "대상의 첫 조회가 끝났다"만 알린다
  await Promise.allSettled([loadOverview(), loadActivity(), loadMetrics(), loadBackupInfo(), runQuery(), loadSlow(), loadReplication(), loadWaitEvents(), loadSessions(), loadLatencyPercentiles(), loadSloReport(), loadPartitions(), loadAdvisors(), loadAiOperations(), loadFinOps(), loadAnomalies(), loadPlanChanges(), loadDeadlocks(), loadReviews(), loadConversationsAndOpenLatest(instance.id)]);
}

// ---------- Advisors (D2) — 자동 점검 결과를 심각도별로 표시 ----------
// 읽고 조언만 하는 진단이라 VIEWER도 조회 가능. 각 Advisor는 OK/위반/미지원/오류로 정직하게 표기한다.
const SEV_LABEL = { CRITICAL: "치명", WARNING: "경고", INFO: "정보" };
const STATUS_LABEL = { OK: "통과", VIOLATIONS: "지적", UNSUPPORTED: "미지원", ERROR: "오류", SHARED: "서버 공유" };

async function loadAdvisors() {
  const summary = $("#advisors-summary");
  const box = $("#advisors-result");
  summary.innerHTML = "";
  box.classList.add("muted");
  box.textContent = "점검 중...";
  let report;
  try {
    report = await api(`/api/instances/${state.instance.id}/advisors`);
  } catch (e) {
    box.textContent = `점검 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="sev-badge sev-CRITICAL">치명 ${report.critical}</span>
    <span class="sev-badge sev-WARNING">경고 ${report.warning}</span>
    <span class="sev-badge sev-INFO">정보 ${report.info}</span>
    <span class="advisors-time muted">점검 ${esc(String(report.checkedAt).replace("T", " ").slice(0, 19))}</span>`;

  // 지적이 있는 Advisor를 먼저(나쁜 순), 그다음 통과/미지원 순으로 정렬한다.
  const order = { VIOLATIONS: 0, ERROR: 1, OK: 2, UNSUPPORTED: 3 };
  const checks = [...report.checks].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));

  box.innerHTML = checks.map((c) => {
    const findings = (c.findings || []).map((f) => `
      <div class="advisor-finding sev-border-${esc(f.severity)}">
        <div class="advisor-finding-head">
          <span class="sev-badge sev-${esc(f.severity)}">${esc(SEV_LABEL[f.severity] ?? f.severity)}</span>
          <span class="advisor-finding-title">${esc(f.title)}</span>
        </div>
        <div class="advisor-finding-detail">${esc(f.detail)}</div>
        <div class="advisor-finding-reco"><strong>권고:</strong> ${esc(f.recommendation)}</div>
      </div>`).join("");
    const note = c.note && c.status !== "VIOLATIONS"
      ? `<div class="advisor-note muted">${esc(c.note)}</div>` : "";
    return `
      <div class="advisor-check status-${esc(c.status)}">
        <div class="advisor-check-head">
          <span class="advisor-status advisor-status-${esc(c.status)}">${esc(STATUS_LABEL[c.status] ?? c.status)}</span>
          <span class="advisor-check-title">${esc(c.title)}</span>
        </div>
        ${findings}${note}
      </div>`;
  }).join("");
}

// ---------- AI 운영 작업 (169절) — Slack·경보에서 시작된 비동기 진단을 사람이 보는 화면 ----------
// 사실(DBTower가 모음)과 AI 소견(모델이 만듦)을 한 덩어리로 보여주지 않는다. 검증 안 된 수치가 있으면 소견보다 먼저 세운다.
const AIOP_TYPE_LABEL = {
  QUERY_DIAGNOSIS: "쿼리 진단", REGRESSION_EXPLANATION: "회귀 원인", BACKUP_RISK_REVIEW: "백업 위험",
  SLO_RISK_REVIEW: "SLO 위험", ADVISOR_SUMMARY: "Advisor 요약", COST_REVIEW: "비용 검토",
  INCIDENT_TRIAGE: "장애 초기 진단", DB_TEAM_INQUIRY: "DB팀 문의", PERIODIC_REPORT: "정기 리포트",
};
const AIOP_STATUS_LABEL = {
  RECEIVED: "접수", AUTHORIZED: "선점", COLLECTING: "사실 수집", RETRIEVING: "자료 검색",
  ANALYZING: "AI 분석", VERIFYING: "검증", COMPLETED: "완료", FAILED: "실패", CANCELLED: "취소",
};
const AIOP_TRIGGER_LABEL = { WEB: "웹", SLACK: "Slack", ALERT: "경보", SCHEDULE: "스케줄", API: "API" };
const AIOP_ACTIVE = new Set(["RECEIVED", "AUTHORIZED", "COLLECTING", "RETRIEVING", "ANALYZING", "VERIFYING"]);
const aiops = { jobs: [], openId: null, timer: null };

async function loadAiOperations() {
  const body = $("#aiops-table tbody");
  if (!body) return;
  let jobs;
  try {
    jobs = await api("/api/ai-operations?limit=30");
  } catch (e) {
    $("#aiops-status").textContent = `조회 실패: ${e.message}`;
    body.innerHTML = `<tr><td colspan="6" class="muted">조회 실패</td></tr>`;
    return;
  }
  aiops.jobs = jobs;
  renderAiOperations();
  scheduleAiOpsRefresh();
}

function renderAiOperations() {
  const body = $("#aiops-table tbody");
  const all = $("#aiops-all-instances")?.checked;
  const rows = aiops.jobs.filter((j) => all || !state.instance || j.instanceId === state.instance.id);
  const active = rows.filter((j) => AIOP_ACTIVE.has(j.status)).length;
  $("#aiops-status").textContent = rows.length
    ? `${rows.length}건 표시${active ? ` — 진행 중 ${active}건은 5초마다 갱신합니다` : ""}`
    : "작업이 없습니다 — Slack에서 인스턴스 이름과 함께 물어보면 여기에 쌓입니다";
  if (!rows.length) {
    body.innerHTML = `<tr><td colspan="6" class="muted">표시할 작업이 없습니다</td></tr>`;
    $("#aiops-detail").hidden = true;
    return;
  }
  body.innerHTML = rows.map((j) => {
    const name = j.instanceId ? (state.instances.find((i) => i.id === j.instanceId)?.name ?? `#${j.instanceId}`) : "범위 전체";
    return `<tr class="aiop-row${j.jobId === aiops.openId ? " open" : ""}" data-job="${esc(j.jobId)}">
      <td><span class="aiop-badge aiop-${esc(j.status)}">${esc(AIOP_STATUS_LABEL[j.status] ?? j.status)}</span></td>
      <td>${esc(AIOP_TYPE_LABEL[j.type] ?? j.type)}</td>
      <td>${esc(name)}</td>
      <td>${esc(j.requester)} <span class="muted">(${esc(AIOP_TRIGGER_LABEL[j.trigger] ?? j.trigger)})</span></td>
      <td>${esc(String(j.requestedAt).replace("T", " ").slice(0, 19))}</td>
      <td><button type="button" class="btn btn-small aiop-open">보기</button></td>
    </tr>`;
  }).join("");
  body.querySelectorAll(".aiop-row").forEach((tr) => {
    tr.querySelector(".aiop-open").addEventListener("click", () => openAiOperation(tr.dataset.job));
  });
  if (aiops.openId && rows.some((j) => j.jobId === aiops.openId)) openAiOperation(aiops.openId, { quiet: true });
}

// 참고 자료 본문은 600자짜리 런북 발췌라 화면에서는 줄인다 — 전체는 API 응답에 그대로 있다
const AIOP_ITEM_CAP = 240;
const aiopList = (title, items, extra = "", cap = 0) => (items && items.length)
  ? `<div class="aiop-block ${extra}"><h4>${esc(title)}</h4><ul>${items.map((i) => {
      const text = cap && i.length > cap ? `${i.slice(0, cap)}...` : i;
      return `<li${cap && i.length > cap ? ` title="${esc(i)}"` : ""}>${esc(text)}</li>`;
    }).join("")}</ul></div>` : "";

async function openAiOperation(jobId, opts = {}) {
  const box = $("#aiops-detail");
  aiops.openId = jobId;
  if (!opts.quiet) { box.hidden = false; box.innerHTML = `<div class="muted">불러오는 중...</div>`; }
  let job;
  try {
    job = await api(`/api/ai-operations/${encodeURIComponent(jobId)}`);
  } catch (e) {
    box.hidden = false;
    box.innerHTML = `<div class="muted">작업을 열지 못했습니다: ${esc(e.message)}</div>`;
    return;
  }
  const r = job.result;
  const canRetry = job.status === "FAILED" && state.caps.has("TARGET_OPERATE");
  // 취소는 요청자 본인이나 운영자만 된다(서버가 403으로 막는다) — 눌러서 403을 받는 버튼을 만들지 않는다
  const canCancel = AIOP_ACTIVE.has(job.status)
    && (job.requester === state.username || state.caps.has("TARGET_OPERATE"));
  const head = `
    <div class="aiop-detail-head">
      <span class="aiop-badge aiop-${esc(job.status)}">${esc(AIOP_STATUS_LABEL[job.status] ?? job.status)}</span>
      <strong>${esc(AIOP_TYPE_LABEL[job.type] ?? job.type)}</strong>
      <span class="muted">작업 ${esc(job.jobId.slice(0, 8))} · 시도 ${esc(String(job.attempt))} · 구간 ${esc(String(job.windowFrom).slice(11, 19))}~${esc(String(job.windowTo).slice(11, 19))}</span>
      <span class="aiop-actions">
        ${canCancel ? `<button type="button" class="btn btn-small" id="aiop-cancel">취소</button>` : ""}
        ${canRetry ? `<button type="button" class="btn btn-small" id="aiop-retry">재시도</button>` : ""}
      </span>
    </div>
    <div class="aiop-prompt"><span class="muted">요청:</span> ${esc(job.prompt)}</div>`;
  const failure = job.failureReason ? `<div class="aiop-block aiop-warn"><h4>실패 사유</h4><p>${esc(job.failureReason)}</p></div>` : "";
  let result = "";
  if (r) {
    const opinion = r.aiOpinion
      ? `<div class="aiop-block aiop-opinion"><h4>AI 1차 소견 <span class="muted">(판단은 사람이 합니다 · ${esc(r.backend ?? "")} · ${esc(r.promptVersion ?? "")})</span></h4><p>${esc(r.aiOpinion)}</p></div>`
      : `<div class="aiop-block muted"><h4>AI 1차 소견</h4><p>없음 — 규칙 판정만 제공합니다</p></div>`;
    result = [
      aiopList("검증되지 않은 내용", r.unverifiedClaims, "aiop-warn"),
      aiopList("규칙 판정", r.ruleFindings),
      opinion,
      aiopList("근거", r.evidence),
      aiopList("불확실한 점", r.uncertainties),
      aiopList("다음 조치", r.nextActions),
      r.approvalRequired ? `<div class="aiop-block aiop-warn"><h4>승인</h4><p>대상 DB를 바꿀 수 있는 조치가 언급됐습니다. 실행은 워크벤치 변경 요청으로 승인을 받습니다.</p></div>` : "",
      aiopList("DBTower가 모은 사실", r.facts),
      aiopList("참고 자료 (과거 사례·런북 — 현재 사실이 아닙니다)", r.references, "", AIOP_ITEM_CAP),
    ].join("");
  } else if (AIOP_ACTIVE.has(job.status)) {
    result = `<div class="aiop-block muted"><p>진행 중입니다. 사실을 모으고 분석이 끝나면 여기에 결과가 붙습니다.</p></div>`;
  }
  box.hidden = false;
  box.innerHTML = head + failure + result;
  $("#aiop-cancel")?.addEventListener("click", () => aiOperationAction(jobId, "cancel"));
  $("#aiop-retry")?.addEventListener("click", () => aiOperationAction(jobId, "retry"));
  document.querySelectorAll(".aiop-row").forEach((tr) => tr.classList.toggle("open", tr.dataset.job === jobId));
}

async function aiOperationAction(jobId, action) {
  try {
    await api(`/api/ai-operations/${encodeURIComponent(jobId)}/${action}`, { method: "POST" });
  } catch (e) {
    $("#aiops-status").textContent = `${action === "cancel" ? "취소" : "재시도"} 실패: ${e.message}`;
    return;
  }
  await loadAiOperations();
  openAiOperation(jobId);
}

// 진행 중 작업이 있고 이 카드가 실제로 보일 때만 갱신한다 — 안 보는 화면이 폴링을 계속하면 서버만 바쁘다
function setupAiOperations() {
  $("#aiops-all-instances")?.addEventListener("change", renderAiOperations);
  $("#btn-aiop-submit")?.addEventListener("click", submitAiOperation);
  document.addEventListener("visibilitychange", scheduleAiOpsRefresh);
}

// 같은 칸의 채팅(자연어 진단)과 달리, 여기서 만든 작업은 사실 수집·검증을 거쳐 나중에 결과가 붙는다.
// 결과는 대화에 한 줄로 남기고, 같은 문장을 화면 낭독기용 상태 영역에도 적는다(눈에는 한 번만 보인다).
// 이 접수 줄은 서버 대화에 저장되지 않는다 — 화면 메모리에만 있고 대화를 바꾸면 사라진다(결과는 진단 탭 카드에 남는다).
async function submitAiOperation() {
  const status = $("#aiop-submit-status");
  const btn = $("#btn-aiop-submit");
  const input = $("#diagnose-question");
  const inst = state.instance;
  const prompt = input.value.trim();
  if (!inst || !prompt || btn.dataset.busy) return;
  const turns = chatTurns();
  const say = (text, jobId) => {
    status.textContent = text;
    // 접수 도중 다른 인스턴스로 옮겨도 그 작업을 맡긴 대상의 대화에 남긴다
    turns.push({ role: "system", text, jobId });
    if (state.instance?.id === inst.id) renderChat({ follow: true });
  };
  const type = $("#aiop-new-type").value;
  btn.dataset.busy = "1";
  btn.disabled = true;
  status.textContent = "접수 중...";
  let accepted = null;
  try {
    accepted = await api("/api/ai-operations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ type, instanceId: inst.id,
        windowMinutes: Number($("#aiop-new-window").value), prompt, trigger: "WEB" }),
    });
    turns.push({ role: "user", text: prompt });
    input.value = "";
    autoGrowChatInput();
    closeAiOpPopover();
    say(`${AIOP_TYPE_LABEL[accepted.type] ?? accepted.type} 작업 ${String(accepted.jobId ?? "").slice(0, 8)}을 접수했습니다. 사실 수집과 검증이 끝나면 결과가 붙습니다.`, accepted.jobId);
    showMonGroup("diag");
    document.querySelector('.tab[data-tab="monitor"]')?.click();
    await loadAiOperations();
    if (accepted.jobId) openAiOperation(accepted.jobId);
  } catch (e) {
    // 접수까지는 됐는데 그 뒤가 실패한 경우를 "접수 실패"로 적지 않는다 — 작업은 이미 만들어져 돌고 있다
    say(accepted
      ? `접수는 됐지만 작업 목록을 갱신하지 못했습니다: ${e.message}`
      : `작업을 접수하지 못했습니다: ${e.message}`, accepted?.jobId);
  } finally {
    delete btn.dataset.busy;
    syncChatComposer();
  }
}

function scheduleAiOpsRefresh() {
  clearTimeout(aiops.timer);
  const card = document.querySelector(".aiops-card");
  const visible = document.visibilityState === "visible" && card && card.offsetParent !== null;
  const active = aiops.jobs.some((j) => AIOP_ACTIVE.has(j.status));
  if (!visible || !active) return;
  aiops.timer = setTimeout(loadAiOperations, 5000);
}

// ---------- 비용/효율 FinOps (D6) — 낭비 후보를 종류별로, 신호까지만(절감액 산출 없음) ----------
// 미사용/중복 인덱스·큰 테이블·오버프로비저닝을 "낭비 후보"로 모은다. 대상 DB는 바꾸지 않는다(읽고 조언만).
// 사용 통계를 신뢰성 있게 못 얻는 기종(Oracle 미사용 인덱스 등)은 UNSUPPORTED로 정직하게 표기한다.
const FINOPS_STATUS_LABEL = { OK: "후보 없음", CANDIDATES: "후보", UNSUPPORTED: "미지원", ERROR: "오류" };
const WASTE_KIND_LABEL = {
  UNUSED_INDEX: "미사용 인덱스", REDUNDANT_INDEX: "중복·잉여 인덱스", LARGE_TABLE: "큰 테이블",
  OVER_INDEXED: "과다 인덱싱", CONNECTION_HEADROOM: "연결 여유", MEMORY_HEADROOM: "메모리 여유",
};

async function loadFinOps() {
  const summary = $("#finops-summary");
  const box = $("#finops-result");
  summary.innerHTML = "";
  box.classList.add("muted");
  box.textContent = "분석 중...";
  let report;
  try {
    report = await api(`/api/instances/${state.instance.id}/finops`);
  } catch (e) {
    box.textContent = `분석 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="sev-badge sev-WARNING">낭비 후보 ${report.candidateCount}</span>
    <span class="advisors-time muted">신호까지만(절감액 산출 없음) · 분석 ${esc(String(report.generatedAt).replace("T", " ").slice(0, 19))}</span>`;

  // 후보가 있는 분석기를 먼저, 그다음 오류/후보없음/미지원 순으로 정렬한다.
  const order = { CANDIDATES: 0, ERROR: 1, OK: 2, UNSUPPORTED: 3 };
  const checks = [...report.checks].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9));

  box.innerHTML = checks.map((c) => {
    const cands = (c.candidates || []).map((w) => `
      <div class="advisor-finding sev-border-${esc(w.severity)}">
        <div class="advisor-finding-head">
          <span class="sev-badge sev-${esc(w.severity)}">${esc(SEV_LABEL[w.severity] ?? w.severity)}</span>
          <span class="finops-kind">${esc(WASTE_KIND_LABEL[w.kind] ?? w.kind)}</span>
          <span class="advisor-finding-title">${esc(w.target)}</span>
        </div>
        <div class="advisor-finding-detail">${esc(w.evidence)}</div>
        <div class="advisor-finding-reco"><strong>검토:</strong> ${esc(w.recommendation)}</div>
      </div>`).join("");
    const note = c.note && c.status !== "CANDIDATES"
      ? `<div class="advisor-note muted">${esc(c.note)}</div>` : "";
    return `
      <div class="advisor-check status-${esc(c.status)}">
        <div class="advisor-check-head">
          <span class="advisor-status advisor-status-${esc(c.status)}">${esc(FINOPS_STATUS_LABEL[c.status] ?? c.status)}</span>
          <span class="advisor-check-title">${esc(c.title)}</span>
        </div>
        ${cands}${note}
      </div>`;
  }).join("");
}

// ---------- 통합 헬스 스코어 (D8) — 흩어진 신호를 인스턴스별 한 점수로, 나쁜 순으로 ----------
// 인스턴스 선택과 무관한 함대 전체 뷰. "어디부터 볼지"를 서버가 정렬해 내려주고, 행 클릭 시 감점 사유를 분해한다.
const SCORE_SIGNAL_LABEL = {
  HEALTH: "가용성", ANOMALY: "이상 감지", ADVISOR: "Advisors", SLO: "SLO / 버짓", BACKUP: "백업 신선도",
  // CPU가 아니라 "동시 실행 압박" — 기종마다 세는 단위가 달라 요약 문구에 무엇을 읽었는지 담긴다(162절)
  RESOURCE: "자원 압박",
};
const SCORE_STATE_LABEL = {
  OK: "정상", PENALIZED: "감점", INSUFFICIENT_DATA: "데이터 부족", ERROR: "수집 실패",
};

async function loadHealthScore() {
  const summary = $("#score-summary");
  const box = $("#score-result");
  let report;
  try {
    report = await api("/api/health-score");
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  const g = report.gradeCounts || {};
  summary.innerHTML = `
    ${["A", "B", "C", "D", "F"].map((k) => `<span class="grade-badge grade-${k}">${k} ${g[k] ?? 0}</span>`).join("")}
    ${report.partialCount ? `<span class="score-partial">부분 데이터 ${report.partialCount}</span>` : ""}
    <span class="score-time muted">집계 ${esc(String(report.generatedAt).replace("T", " ").slice(0, 19))}</span>`;

  if (!report.instances.length) {
    box.innerHTML = `<div class="muted">${esc(emptyAggregateNote())}</div>`;
    return;
  }
  // 이미 서버가 나쁜 순으로 정렬해 내려준다 — 죽은 것·백업 없는 것이 위로 온다
  const rows = report.instances.map((s) => {
    // 주요 감점 사유: 감점 있는 신호만, 큰 순(서버가 이미 정렬). 없으면 정상 표기.
    const penalized = s.contributions.filter((c) => c.state === "PENALIZED");
    const reasons = penalized.length
      ? penalized.map((c) => `${SCORE_SIGNAL_LABEL[c.signal] ?? c.signal} −${fmtNum(c.penalty, 0)}`).join(" · ")
      : '<span class="muted">감점 없음</span>';
    // 행 클릭 시 펼칠 신호별 기여 분해(투명성) — 데이터 부족·수집 실패도 그대로 노출. 첫 줄은 기종(아이콘이 행에 있으니 배지는 여기로).
    const detail =
      `<div class="score-contrib"><span class="score-contrib-signal">기종</span>`
      + `<span class="type-badge type-${esc(s.type)}">${esc(s.type)}</span>`
      + `<span class="score-contrib-penalty"></span><span class="score-contrib-summary">${esc(s.instanceName)}</span></div>`
      + s.contributions.map((c) => `
      <div class="score-contrib score-state-${esc(c.state)}">
        <span class="score-contrib-signal">${esc(SCORE_SIGNAL_LABEL[c.signal] ?? c.signal)}</span>
        <span class="score-contrib-state score-state-badge-${esc(c.state)}">${esc(SCORE_STATE_LABEL[c.state] ?? c.state)}</span>
        <span class="score-contrib-penalty">${c.penalty > 0 ? `−${fmtNum(c.penalty, 0)}` : ""}</span>
        <span class="score-contrib-summary">${esc(c.summary)}</span>
      </div>`).join("");
    return `
      <tbody class="score-group" data-id="${s.instanceId}">
        <tr class="score-row score-grade-${esc(s.grade)}">
          <td><span class="cell-inst">${engineIcon(s.type)} ${esc(s.instanceName)}</span>
            ${s.down ? '<span class="score-down">DOWN</span>' : ""}
            ${s.partial ? '<span class="score-partial-dot" title="일부 신호가 데이터 부족·수집 실패">부분</span>' : ""}</td>
          <td class="num score-num">${s.score}<span class="score-outof">/100</span></td>
          <td><span class="grade-badge grade-${esc(s.grade)}">${esc(s.grade)}</span></td>
          <td class="score-reasons">${reasons}</td>
        </tr>
        <tr class="score-detail-row" hidden><td colspan="4"><div class="row-detail-fit"><div class="score-detail">${detail}</div></div></td></tr>
      </tbody>`;
  }).join("");
  box.innerHTML = `
    <div class="table-scroll">
      <table class="qtable score-table">
        <thead><tr><th>인스턴스</th><th>점수</th><th>등급</th><th>주요 감점 사유 (클릭 시 분해)</th></tr></thead>
        ${rows}
      </table>
    </div>`;
  // 행 클릭 → 신호별 기여 분해 토글
  box.querySelectorAll(".score-row").forEach((row) => {
    row.addEventListener("click", () => {
      const detailRow = row.parentElement.querySelector(".score-detail-row");
      detailRow.hidden = !detailRow.hidden;
      row.classList.toggle("score-row-open", !detailRow.hidden);
      // 펼친 뒤(=레이아웃이 잡힌 뒤) 폭을 맞춘다 — 숨은 동안에는 clientWidth가 0이라 미리 맞출 수 없다
      if (!detailRow.hidden) fitRowDetails();
    });
  });
}

// ---------- 백업 신선도 (D7) — 전 인스턴스를 한 표로, 오래된 것/백업 없는 것을 강조 ----------
// 인스턴스 선택과 무관한 함대 전체 뷰. "백업했다"가 아니라 "지금 최신이고 복원되는가"를 상시 비춘다.
const FRESHNESS_LABEL = { FRESH: "신선", STALE: "오래됨", NO_BACKUP: "백업 없음" };

async function loadBackupFreshness() {
  const summary = $("#freshness-summary");
  const box = $("#freshness-result");
  let report;
  try {
    report = await api("/api/backup-freshness");
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  box.classList.remove("muted");
  summary.innerHTML = `
    <span class="fresh-badge fresh-FRESH">신선 ${report.freshCount}</span>
    <span class="fresh-badge fresh-STALE">오래됨 ${report.staleCount}</span>
    <span class="fresh-badge fresh-NO_BACKUP">백업 없음 ${report.noBackupCount}</span>
    <span class="freshness-time muted">임계 ${report.thresholdHours}h · 집계 ${esc(String(report.checkedAt).replace("T", " ").slice(0, 19))}</span>`;

  if (!report.instances.length) {
    box.innerHTML = `<div class="muted">${esc(emptyAggregateNote())}</div>`;
    return;
  }
  // 이미 서버가 나쁜 순으로 정렬해 내려준다 — 오래된 것/백업 없는 것이 위로 온다
  const rows = report.instances.map((f) => {
    const last = f.lastBackupAt ? esc(String(f.lastBackupAt).replace("T", " ").slice(0, 19)) : "—";
    const elapsed = f.elapsedHours == null ? "—" : `${fmtNum(f.elapsedHours, 1)}h`;
    const verify = f.verifyStatus
      ? `<span class="verify-badge verify-${esc(f.verifyStatus)}">${esc(f.verifyStatus)}</span>`
      : '<span class="muted">미검증</span>';
    // 3-2-1의 오프사이트 — 로컬 성공과 원격 보관은 별개 사실이라 따로 보여준다
    const remote = f.remoteLocation
      ? `<span class="verify-badge verify-VERIFIED" title="${esc(f.remoteLocation)}">원격 보관</span>`
      : '<span class="muted">로컬만</span>';
    // 클릭 시 펼칠 상세 — 헬스 스코어처럼 그 서버의 상태를 신호별로 분해(가용성은 펼칠 때 조회)
    const inst = state.instances.find((i) => i.id == f.instanceId);
    const server = inst ? `${esc(inst.host)}:${inst.port} / ${esc(inst.dbName)}` : "—";
    const tags = inst ? [inst.environment, inst.region, inst.cluster, inst.teamLabel].filter(Boolean).map(esc).join(" · ") : "";
    const freshState = f.status === "FRESH" ? "OK" : "PENALIZED";
    const verifyState = f.verifyStatus === "VERIFIED" ? "OK" : (f.verifyStatus === "FAILED" ? "PENALIZED" : "INSUFFICIENT_DATA");
    const remoteState = f.remoteLocation ? "OK" : "INSUFFICIENT_DATA";
    const detail =
      `<div class="score-contrib"><span class="score-contrib-signal">기종</span>`
      + `<span class="type-badge type-${esc(f.type)}">${esc(f.type)}</span>`
      + `<span class="score-contrib-penalty"></span><span class="score-contrib-summary">${esc(f.instanceName)}</span></div>`
      + freshContrib("가용성", "INSUFFICIENT_DATA", "조회 중", "핑·응답 조회 중…", `fresh-av-${f.instanceId}`)
      + freshContrib("서버", "OK", "등록", `${server}${tags ? ` · ${tags}` : ""}`)
      + freshContrib("백업 신선도", freshState, FRESHNESS_LABEL[f.status] ?? f.status,
          `마지막 ${last} · ${elapsed} 경과 (임계 ${report.thresholdHours}h)`)
      + freshContrib("복원 검증", verifyState, f.verifyStatus || "미검증",
          f.verifyStatus === "VERIFIED" ? "복원 리허설 통과" : (f.verifyStatus === "FAILED" ? "복원 검증 실패 — 즉시 확인" : "아직 복원 검증 안 됨"))
      + freshContrib("원격 보관", remoteState, f.remoteLocation ? "원격 보관" : "로컬만",
          f.remoteLocation ? `오프사이트: ${f.remoteLocation}` : "3-2-1 오프사이트 사본 없음");
    return `
      <tbody class="fresh-group" data-id="${esc(String(f.instanceId))}">
        <tr class="fresh-row fresh-row-${esc(f.status)}" title="클릭하면 이 서버의 상태를 여기서 펼쳐 봅니다">
          <td><span class="cell-inst">${engineIcon(f.type)} ${esc(f.instanceName)}</span></td>
          <td><span class="fresh-badge fresh-${esc(f.status)}">${esc(FRESHNESS_LABEL[f.status] ?? f.status)}</span></td>
          <td>${last}</td>
          <td class="num">${elapsed}</td>
          <td>${verify}</td>
          <td>${remote}</td>
        </tr>
        <tr class="fresh-detail-row" hidden><td colspan="6"><div class="row-detail-fit"><div class="score-detail">${detail}</div></div></td></tr>
      </tbody>`;
  }).join("");
  box.innerHTML = `
    <div class="table-scroll">
      <table class="qtable freshness-table">
        <thead><tr><th>인스턴스</th><th>신선도</th><th>마지막 백업</th><th>경과</th><th>복원 검증</th><th>원격 보관</th></tr></thead>
        ${rows}
      </table>
    </div>`;
  // 행 클릭 → 그 서버 상태를 인라인으로 펼친다(자동 선택 대신). 처음 펼칠 때 가용성 1회 조회.
  box.querySelectorAll(".fresh-row").forEach((row) => {
    row.addEventListener("click", () => {
      const group = row.parentElement;
      const detailRow = group.querySelector(".fresh-detail-row");
      detailRow.hidden = !detailRow.hidden;
      row.classList.toggle("fresh-row-open", !detailRow.hidden);
      if (!detailRow.hidden) { fitRowDetails(); fillFreshnessHealth(group.dataset.id); }
    });
  });
}

// 펼침 상세의 신호 한 줄 — 헬스 스코어의 기여 분해와 같은 시각(라벨·상태 배지·요약)
function freshContrib(signal, state, stateLabel, summary, id) {
  return `<div class="score-contrib score-state-${state}"${id ? ` id="${esc(id)}"` : ""}>
    <span class="score-contrib-signal">${esc(signal)}</span>
    <span class="score-contrib-state score-state-badge-${state}">${esc(stateLabel)}</span>
    <span class="score-contrib-penalty"></span>
    <span class="score-contrib-summary">${esc(summary)}</span>
  </div>`;
}

// 펼칠 때 그 서버의 가용성(핑·버전)을 1회 조회해 '가용성' 줄만 갱신 — 함대가 커도 보이는 만큼만 조회
async function fillFreshnessHealth(id) {
  const row = $(`#fresh-av-${id}`);
  if (!row || row.dataset.filled) return;
  row.dataset.filled = "1";
  try {
    const h = await api(`/api/instances/${id}/health`);
    const st = h.up ? "OK" : "PENALIZED";
    row.className = `score-contrib score-state-${st}`;
    row.querySelector(".score-contrib-state").className = `score-contrib-state score-state-badge-${st}`;
    row.querySelector(".score-contrib-state").textContent = h.up ? "가용" : "응답 없음";
    row.querySelector(".score-contrib-summary").textContent = h.up
      ? `핑 ${h.pingMillis}ms · ${shortVersion(h.version)}` : (h.message || "접속 실패");
  } catch (e) {
    row.querySelector(".score-contrib-state").textContent = "조회 실패";
    row.querySelector(".score-contrib-summary").textContent = e.message;
    row.dataset.filled = "";
  }
}

// ---------- 활동 그래프 (드래그 구간 선택) ----------
async function loadActivity() {
  const now = new Date();
  const from = toApiTime(new Date(now - 3 * 3600 * 1000)); // 최근 3시간
  const to = toApiTime(now);
  state.activity = await api(`/api/instances/${state.instance.id}/activity?from=${from}&to=${to}`);
  drawChart();
  // Monitoring 탭 Metric 카드의 Query Activity — 같은 데이터를 병치(스냅샷 차분 QPS)
  drawSimpleChart("#qps-chart", "#qps-empty",
    state.activity.map((p) => ({ time: p.time, value: p.qps })), "#22a06b",
    "이 구간에 수집된 스냅샷이 없습니다");
}

// Metric 그래프 (CPU%·Connections) — Prometheus exporter 시계열. 미수집은 사유를 그대로 보여준다.
async function loadMetrics() {
  const now = new Date();
  const from = toApiTime(new Date(now - 3 * 3600 * 1000));
  const to = toApiTime(now);
  let m;
  try {
    m = await api(`/api/instances/${state.instance.id}/metrics?from=${from}&to=${to}`);
  } catch (e) {
    m = { cpu: [], cpuNote: `조회 실패: ${e.message}`, connections: [], connectionsNote: `조회 실패: ${e.message}` };
  }
  state.metricsCpu = m.cpu ?? [];
  drawSimpleChart("#cpu-chart", "#cpu-empty", m.cpu ?? [], "#e5533d", m.cpuNote, "%", 100);
  drawSimpleChart("#conn-chart", "#conn-empty", m.connections ?? [], "#6672f5", m.connectionsNote);
  if (state.chartMetric === "cpu") drawChart();   // 드래그 차트가 CPU 모드면 새 데이터로 다시 그린다
  loadCommandMetrics(from, to);
}

// 명령/행 연산 세분 차트(레퍼런스 Query Activity·Row Operation 대응) — 차트마다 Mean/Max/Min 범례.
async function loadCommandMetrics(from, to) {
  const box = $("#command-charts");
  let cm;
  try {
    cm = await api(`/api/instances/${state.instance.id}/metrics/commands?from=${from}&to=${to}`);
  } catch (e) {
    box.innerHTML = `<div class="muted">명령별 시계열 조회 실패: ${esc(e.message)}</div>`;
    return;
  }
  if (!cm.series || !cm.series.length) {
    box.innerHTML = `<div class="muted">${esc(cm.note ?? "이 구간에 수집된 시계열이 없습니다")}</div>`;
    return;
  }
  const fmtStat = (v) => v == null ? "-" : fmtNum(v, v >= 10 ? 1 : 2);
  // group 필드로 묶어 렌더(Query Activity·Row Operation) — 쿼리 지표는 쿼리끼리 모여 읽기 쉽게.
  // 원래 인덱스(idx)로 svg id를 매겨 아래 draw 루프와 일치시킨다.
  const groups = {};
  cm.series.forEach((s, idx) => { (groups[s.group || "지표"] ??= []).push({ s, idx }); });
  const chartBox = ({ s, idx }) => `
    <div class="metric-chart-box command-box">
      <h4>${esc(s.name)}</h4>
      <svg id="cmd-chart-${idx}" width="100%" height="120" preserveAspectRatio="none"></svg>
      <div id="cmd-empty-${idx}" class="muted center" hidden></div>
      <div class="cmd-legend"><span>Mean <b>${fmtStat(s.mean)}</b></span>
        <span>Max <b>${fmtStat(s.max)}</b></span><span>Min <b>${fmtStat(s.min)}</b></span></div>
    </div>`;
  box.innerHTML = Object.entries(groups).map(([g, items]) => `
    <div class="command-group">
      <h4 class="command-group-title">${esc(g)}</h4>
      <div class="command-grid">${items.map(chartBox).join("")}</div>
    </div>`).join("");
  cm.series.forEach((s, idx) => {
    drawSimpleChart(`#cmd-chart-${idx}`, `#cmd-empty-${idx}`, s.points ?? [], "#2f9e6e",
      cm.note ?? "이 구간에 수집된 시계열이 없습니다");
  });
}

// ---------- 차트 호버 툴팁 ----------
// 두 렌더러(드래그 차트·단순 차트)가 렌더 끝에 svg._chart에 {pts,x,y,W,H,padT,padB,fmt}를 남기면,
// 공용 호버가 그걸 읽어 커서에 가장 가까운 점의 정확 수치·시각을 세로 가이드선·점·말풍선으로 보여준다.
// 좌표 변환은 getScreenCTM()으로 — viewBox·preserveAspectRatio가 어떻든 화면↔차트 좌표가 정확하다.
const SVGNS = "http://www.w3.org/2000/svg";

function chartTip() {
  let el = document.getElementById("chart-tip");
  if (!el) {
    el = document.createElement("div");
    el.id = "chart-tip";
    el.className = "chart-tip";
    el.hidden = true;
    document.body.appendChild(el);
  }
  return el;
}

// 세로 가이드선 + 점 마커 — svg.innerHTML 재그림에 지워지므로 없으면 다시 붙인다(드래그 중에도 유지)
function chartHoverMarker(svg) {
  let g = svg.querySelector(".hover-marker");
  if (!g) {
    g = document.createElementNS(SVGNS, "g");
    g.setAttribute("class", "hover-marker");
    const line = document.createElementNS(SVGNS, "line");
    line.setAttribute("class", "hover-line");
    const dot = document.createElementNS(SVGNS, "circle");
    dot.setAttribute("class", "hover-dot");
    dot.setAttribute("r", "3.5");
    g.appendChild(line);
    g.appendChild(dot);
    svg.appendChild(g);
  }
  return g;
}

function fmtClock(t) {
  const d = new Date(t);
  const p = (n) => String(n).padStart(2, "0");
  return `${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function attachChartHover(svg) {
  if (svg._hoverAttached) return; // 명령 차트는 매번 새 SVG라 각자 한 번씩 붙는다
  svg._hoverAttached = true;
  const tip = chartTip();
  const hide = () => {
    tip.hidden = true;
    const g = svg.querySelector(".hover-marker");
    if (g) g.remove();
  };
  svg.addEventListener("pointermove", (ev) => {
    const c = svg._chart;
    const ctm = svg.getScreenCTM();
    if (!c || !c.pts || c.pts.length < 2 || !ctm) { hide(); return; }
    // 커서 화면좌표 → 차트좌표. 가장 가까운 점은 x(시간축)만으로 찾는다.
    const local = new DOMPoint(ev.clientX, ev.clientY).matrixTransform(ctm.inverse());
    let best = c.pts[0], bd = Infinity;
    for (const p of c.pts) {
      const d = Math.abs(c.x(p.t) - local.x);
      if (d < bd) { bd = d; best = p; }
    }
    const cx = c.x(best.t), cy = c.y(best.v);
    const g = chartHoverMarker(svg);
    const line = g.querySelector(".hover-line");
    line.setAttribute("x1", cx); line.setAttribute("x2", cx);
    line.setAttribute("y1", c.padT); line.setAttribute("y2", c.H - c.padB);
    const dot = g.querySelector(".hover-dot");
    dot.setAttribute("cx", cx); dot.setAttribute("cy", cy);
    // 점 화면좌표에 말풍선 — position:fixed라 getScreenCTM 결과(뷰포트 기준)를 그대로 쓴다
    const sp = new DOMPoint(cx, cy).matrixTransform(ctm);
    tip.innerHTML = `<b>${c.fmt(best.v)}</b><span>${fmtClock(best.t)}</span>`;
    tip.hidden = false;
    tip.style.left = sp.x + "px";
    tip.style.top = (sp.y - 12) + "px";
  });
  svg.addEventListener("pointerleave", hide);
}

// 단순 라인 차트 (Metric 카드용) — 드래그 차트와 달리 선택 하이라이트가 없다.
// fixedMax를 주면 Y축 상한을 고정한다(CPU%는 100 고정 — 스파이크가 없어도 척도가 일정해 읽기 쉽다).
function drawSimpleChart(svgSel, emptySel, pts, color, note, unit = "", fixedMax = null) {
  const svg = $(svgSel);
  const empty = $(emptySel);
  const W = 1000, H = 140, padL = 46, padR = 10, padT = 10, padB = 20;
  svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
  if (!pts || pts.length < 2) {
    svg.innerHTML = "";
    svg._chart = null;
    // 빈 차트 상자(140px)를 남기면 안내 문구가 그 아래 따로 떠 자리만 차지했다(139절) — 비어 있는 동안은 상자를 감추고 안내만 낮은 자리표시로.
    // SVG 요소에는 hidden 프로퍼티가 없어(HTMLElement 전용) svg.hidden = true는 속성을 만들지 않았다 — 속성을 직접 토글한다
    svg.toggleAttribute("hidden", true);
    empty.hidden = false;
    empty.textContent = note ?? "이 구간에 수집된 시계열이 없습니다";
    return;
  }
  svg.toggleAttribute("hidden", false);
  empty.hidden = true;
  const t0 = parseApiTime(pts[0].time).getTime();
  const t1 = parseApiTime(pts[pts.length - 1].time).getTime();
  const maxV = fixedMax ?? Math.max(...pts.map((p) => p.value), 1);
  const x = (t) => padL + (t - t0) / Math.max(t1 - t0, 1) * (W - padL - padR);
  const y = (v) => H - padB - v / maxV * (H - padT - padB);
  const yRatios = fixedMax ? [0, 0.25, 0.5, 0.75, 1] : [0, 0.5, 1];
  const yTicks = yRatios.map((r) => {
    const v = maxV * r;
    return `<line x1="${padL}" y1="${y(v)}" x2="${W - padR}" y2="${y(v)}" stroke="#eef0f3"/>
            <text x="${padL - 6}" y="${y(v) + 4}" text-anchor="end" font-size="10" fill="#7b8494">${fmtNum(v, v >= 10 ? 0 : 1)}</text>`;
  }).join("");
  const xTicks = [0, 1 / 3, 2 / 3, 1].map((r) => {
    const d = new Date(t0 + (t1 - t0) * r);
    const label = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    return `<text x="${x(d.getTime())}" y="${H - 6}" text-anchor="middle" font-size="10" fill="#7b8494">${label}</text>`;
  }).join("");
  const line = pts.map((p, i) =>
    `${i === 0 ? "M" : "L"}${x(parseApiTime(p.time).getTime()).toFixed(1)},${y(p.value).toFixed(1)}`).join(" ");
  svg.innerHTML = `${yTicks}${xTicks}<path d="${line}" fill="none" stroke="${color}" stroke-width="1.8"/>`;
  svg._chart = {
    pts: pts.map((p) => ({ t: parseApiTime(p.time).getTime(), v: p.value })),
    W, H, padT, padB, x, y, fmt: (v) => fmtNum(v, v >= 10 ? 0 : 1) + unit,
  };
  attachChartHover(svg);
}

const CHART = { w: 1000, h: 180, padL: 46, padR: 10, padT: 12, padB: 22 };

// 드래그 차트의 데이터 시리즈 — QPS(스냅샷 차분) 또는 CPU%(Prometheus). 레퍼런스는 CPU 그래프에서 드래그한다.
// t는 parseApiTime을 거친 진짜 epoch(ms) — 축 라벨·드래그 선택이 브라우저 로컬로 일관되게 나온다.
function chartSeries() {
  if (state.chartMetric === "cpu") {
    return (state.metricsCpu ?? []).map((p) => ({ t: parseApiTime(p.time).getTime(), v: p.value }));
  }
  return state.activity.map((p) => ({ t: parseApiTime(p.time).getTime(), v: p.qps }));
}

function chartScales() {
  const pts = chartSeries();
  const t0 = pts[0].t;
  const t1 = pts[pts.length - 1].t;
  // CPU 모드는 0~100% 고정축(척도 일정), QPS는 데이터 최대에 맞춰 자동
  const maxQ = state.chartMetric === "cpu" ? 100 : Math.max(...pts.map((p) => p.v), 1);
  const x = (t) => CHART.padL + (t - t0) / Math.max(t1 - t0, 1) * (CHART.w - CHART.padL - CHART.padR);
  const y = (q) => CHART.h - CHART.padB - q / maxQ * (CHART.h - CHART.padT - CHART.padB);
  const invX = (px) => t0 + (px - CHART.padL) / (CHART.w - CHART.padL - CHART.padR) * (t1 - t0);
  return { t0, t1, maxQ, x, y, invX };
}

function drawChart() {
  const svg = $("#activity-chart");
  const pts = chartSeries();
  svg.setAttribute("viewBox", `0 0 ${CHART.w} ${CHART.h}`);
  if (pts.length < 2) {
    svg.innerHTML = "";
    svg._chart = null;
    // 빈 상자를 180px 남기면 안내 한 줄이 그 아래 떠 자리만 차지한다(139절의 drawSimpleChart와 같은 규칙).
    // SVG에는 hidden 프로퍼티가 없어(HTMLElement 전용) 속성을 직접 토글한다
    svg.toggleAttribute("hidden", true);
    $("#chart-empty").hidden = false;
    $("#chart-empty").textContent = state.chartMetric === "cpu"
      ? "CPU 시계열이 없습니다 (node_exporter/Prometheus 미수집)" : "이 구간에 수집된 스냅샷이 없습니다";
    return;
  }
  svg.toggleAttribute("hidden", false);
  $("#chart-empty").hidden = true;
  const s = chartScales();

  // 선택 구간 하이라이트 (조회=초록, 비교=주황)
  const selRect = (sel, color) => sel
    ? `<rect x="${s.x(sel.from.getTime())}" y="${CHART.padT}"
        width="${Math.max(s.x(sel.to.getTime()) - s.x(sel.from.getTime()), 2)}"
        height="${CHART.h - CHART.padT - CHART.padB}" fill="${color}" opacity="0.22"/>` : "";

  // y축 눈금 — CPU(고정 0~100%)는 5개, QPS(자동)는 3개
  const yRatios = state.chartMetric === "cpu" ? [0, 0.25, 0.5, 0.75, 1] : [0, 0.5, 1];
  const yTicks = yRatios.map((r) => {
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
    `${i === 0 ? "M" : "L"}${s.x(p.t).toFixed(1)},${s.y(p.v).toFixed(1)}`).join(" ");

  svg.innerHTML = `
    ${yTicks}${xTicks}
    ${selRect(state.selections.base, "#f08c2d")}
    ${selRect(state.selections.target, "#22a06b")}
    <path d="${line}" fill="none" stroke="#6672f5" stroke-width="1.8"/>`;

  // 호버 정보 — QPS면 "q/s", CPU 모드면 "%" 단위로 정확 수치를 띄운다
  const unit = state.chartMetric === "cpu" ? "%" : " q/s";
  svg._chart = {
    pts, W: CHART.w, H: CHART.h, padT: CHART.padT, padB: CHART.padB,
    x: s.x, y: s.y, fmt: (v) => fmtNum(v, v >= 10 ? 0 : 1) + unit,
  };
  attachChartHover(svg);
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
    if (!state.dragMode || chartSeries().length < 2) return;
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
  $("#metric-qps").addEventListener("click", () => setChartMetric("qps"));
  $("#metric-cpu").addEventListener("click", () => setChartMetric("cpu"));
  // 차트 헤더 조회 버튼 — 비교 구간까지 드래그했으면 비교 조회, 아니면 단독 조회
  $("#chart-query").addEventListener("click", () => {
    if ($("#base-from").value && $("#base-to").value && state.selections.base) runCompare();
    else runQuery();
  });
}

function toggleDragMode(mode) {
  state.dragMode = state.dragMode === mode ? null : mode;
  $("#mode-target").classList.toggle("active", state.dragMode === "target");
  $("#mode-base").classList.toggle("active", state.dragMode === "base");
}

// 드래그 차트 메트릭 전환 — 구간 선택(드래그)은 어느 그래프에서든 동일하게 동작한다
function setChartMetric(metric) {
  state.chartMetric = metric;
  $("#metric-qps").classList.toggle("active", metric === "qps");
  $("#metric-cpu").classList.toggle("active", metric === "cpu");
  $("#chart-metric-label").textContent = metric === "cpu" ? "CPU %" : "QPS";
  drawChart();
}

// 누적 통계의 행 지표는 기종마다 세는 것이 다르다(MySQL 검사한 행, PostgreSQL 돌려준 행, SQL Server 논리 읽기 등) —
// "읽은 행수"로 뭉뚱그리면 PostgreSQL에서 호출이 늘어난 것을 스캔 증가로 읽게 된다(132절). 서버가 알려준 이름으로 적는다.
const rowsMetricCache = new Map();
function rowsMetricLabel(instanceId) {
  if (!rowsMetricCache.has(instanceId)) {
    rowsMetricCache.set(instanceId, api(`/api/instances/${instanceId}/rows-metric`).then((m) => m.label).catch(() => "행 지표"));
  }
  return rowsMetricCache.get(instanceId);
}
// 1ms 미만 평균은 소수 둘째 자리 표시가 0으로 뭉갠다 — 그 경우만 넷째 자리까지
const msDigits = (...values) => (values.some((v) => v > 0 && v < 1) ? 4 : 2);

// ---------- Top Query: 단순 조회 ----------
async function runQuery() {
  state.compareMode = false;
  closeDetail();
  // 조회 범위 표기(레퍼런스 하단 텍스트 대응) — 단독 조회에서도 어떤 창을 보고 있는지 남긴다.
  // datetime-local 값은 브라우저 로컬(KST) 그대로라 변환 없이 표기만 한다.
  const sum = $("#compare-summary");
  const tf = $("#target-from").value, tt = $("#target-to").value;
  if (tf && tt) {
    sum.hidden = false;
    sum.innerHTML = `<span class="summary-item muted">조회하는 시간 범위(KST): ${esc(tf.replace("T", " "))} ~ ${esc(tt.replace("T", " "))}
      / 비교하는 시간 범위(KST): -</span>`;
  } else {
    sum.hidden = true;
  }
  let stats, rowsLabel;
  try {
    [stats, rowsLabel] = await Promise.all([
      api(`/api/instances/${state.instance.id}/query-stats?limit=20`), rowsMetricLabel(state.instance.id)]);
  } catch (e) {
    // 대상 조회가 실패하면(502) 사유를 표 자리에 보인다 — 전에는 잡지 않아 표가 빈 채로 멈추고 콘솔 오류만 남았다(149절 계측 중 발견)
    $("#top-table thead").innerHTML = "";
    $("#top-table tbody").innerHTML = `<tr><td class="muted">쿼리 통계를 불러오지 못했습니다: ${esc(e.message)}</td></tr>`;
    return;
  }
  const table = $("#top-table");
  // Call/sec는 스냅샷 차분이라 이력 없으면 null → "—". Latency/행 지표는 누적÷호출수(평균).
  // Plan 컬럼은 값이 있는 기종(MongoDB — profiler가 계획 요약을 저장)에서만 그린다.
  const hasPlan = stats.some((q) => q.plan);
  table.querySelector("thead").innerHTML = `
    <tr><th>Load</th><th>Query</th><th class="num">Call/sec</th>
        <th class="num">Latency(ms)</th><th class="num">${esc(rowsLabel)} (평균)</th>${hasPlan ? "<th>Plan</th>" : ""}</tr>`;
  table.querySelector("tbody").innerHTML = stats.map((q, idx) => `
    <tr data-idx="${idx}">
      <td class="num">${fmtNum(q.loadPct)}%</td>
      <td class="qtext" data-sql-tip="${esc(q.queryText)}" tabindex="0" aria-describedby="sql-tip">${queryTextHtml(q.queryText)}</td>
      <td class="num">${q.callsPerSec == null ? '<span class="muted">—</span>' : fmtNum(q.callsPerSec)}</td>
      <td class="num">${fmtNum(q.avgLatencyMs, msDigits(q.avgLatencyMs))}</td>
      <td class="num">${fmtNum(q.rowsExaminedAvg, 0)}</td>
      ${hasPlan ? `<td>${q.plan ? `<span class="plan-badge ${/COLLSCAN/i.test(q.plan) ? "plan-bad" : "plan-ok"}">${esc(q.plan)}</span>` : '<span class="muted">—</span>'}</td>` : ""}
    </tr>`).join("");
  bindRowClicks(stats.map((q) => ({ queryId: q.queryId, queryText: q.queryText })));
}

// ---------- Top Query: 비교 조회 (증감 + NEW) ----------
async function runCompare() {
  const requestSeq = ++state.compareSeq;
  const p = (id) => $(id).value;
  if (!p("#base-from") || !p("#base-to") || !p("#target-from") || !p("#target-to")) return;
  closeDetail();
  const qs = `baseFrom=${toApiTime(p("#base-from"))}&baseTo=${toApiTime(p("#base-to"))}&targetFrom=${toApiTime(p("#target-from"))}&targetTo=${toApiTime(p("#target-to"))}`;
  let result;
  const rowsLabel = await rowsMetricLabel(state.instance.id);
  try {
    result = await api(`/api/instances/${state.instance.id}/compare?${qs}`);
  } catch (e) {
    if (requestSeq !== state.compareSeq) return;
    // 브라우저 경고창은 화면 밖으로 튀어나오고 맥락을 잃는다 — 실패한 자리에 그대로 적는다
    const sum = $("#compare-summary");
    sum.hidden = false;
    sum.innerHTML = `<div class="finding-item">비교하지 못했습니다 — ${esc(e.message)}`
      + `<div class="muted">구간 안에 스냅샷 배치가 2개 이상 있어야 차분을 낼 수 있습니다(수집 주기 1분).`
      + ` 구간을 넓히거나, 수집이 도는 동안 기다린 뒤 다시 조회하세요.</div></div>`;
    return;
  }
  if (requestSeq !== state.compareSeq) return;
  state.compareMode = true;

  // 요약 스트립 — 표를 읽기 전에 "전반적으로 무엇이 변했는지"
  const sum = $("#compare-summary");
  sum.hidden = false;
  const pct = (v) => v == null ? "-" : `<span class="${v >= 0 ? "delta-up" : "delta-down"}">${v >= 0 ? "+" : ""}${fmtNum(v, 0)}%</span>`;
  sum.innerHTML = `
    <span class="summary-item">호출량 ${pct(result.totalCallsChangePct)}</span>
    <span class="summary-item">평균 레이턴시 ${pct(result.avgLatencyChangePct)}</span>
    <span class="summary-item">${esc(rowsLabel)} ${pct(result.rowsExaminedChangePct)}</span>
    <span class="summary-item">신규 쿼리 <b>${result.newQueryCount}</b>개</span>
    <span class="summary-item muted">조회하는 시간 범위(KST): ${esc($("#target-from").value.replace("T", " "))} ~ ${esc($("#target-to").value.slice(11))}
      / 비교하는 시간 범위(KST): ${esc($("#base-from").value.replace("T", " "))} ~ ${esc($("#base-to").value.slice(11))}</span>`;

  // Load(시간 점유율%) = qps×avgMs / Σ(qps×avgMs) — 구간별로 따로 계산해 증감까지 보여준다(레퍼런스 첫 컬럼).
  const loadShare = (rows, qpsKey, msKey) => {
    const total = rows.reduce((s, q) => s + q[qpsKey] * q[msKey], 0);
    return (q) => total === 0 ? 0 : Math.round(q[qpsKey] * q[msKey] / total * 10000) / 100;
  };
  const targetLoad = loadShare(result.queries, "targetQps", "targetAvgMs");
  const baseLoad = loadShare(result.queries, "baseQps", "baseAvgMs");
  const loadPctChange = (b, t) => b === 0 ? null : Math.round((t - b) / b * 10000) / 100;

  // 표: target 부하(Load) 내림차순, 신규 쿼리 하이라이트
  const rows = [...result.queries].sort((a, b) => targetLoad(b) - targetLoad(a));
  const table = $("#top-table");
  table.querySelector("thead").innerHTML = `
    <tr><th class="num">Load</th><th>Query</th><th class="num">QPS</th><th class="num">Latency(ms)</th><th class="num">${esc(rowsLabel)}/call</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.map((q, idx) => `
    <tr data-idx="${idx}" class="${q.newQuery ? "new-query" : ""}">
      <td>${deltaCell(baseLoad(q), targetLoad(q), loadPctChange(baseLoad(q), targetLoad(q)))}</td>
      <td class="qtext" data-sql-tip="${esc(q.queryText)}" tabindex="0" aria-describedby="sql-tip">${q.newQuery ? '<span class="badge-new">NEW</span>' : ""}${queryTextHtml(q.queryText)}</td>
      <td>${deltaCell(q.baseQps, q.targetQps, q.qpsChangePct)}</td>
      <td>${deltaCell(q.baseAvgMs, q.targetAvgMs, q.latencyChangePct, msDigits(q.baseAvgMs, q.targetAvgMs))}</td>
      <td>${deltaCell(q.baseRowsPerCall, q.targetRowsPerCall, q.rowsPerCallChangePct, 0)}</td>
    </tr>`).join("");
  bindRowClicks(rows);
}

// ---------- 쿼리 상세 (EXPLAIN + AI) ----------
// 보기 버튼 다섯은 토글이다(B3). 예전에는 여덟 개가 늘 같은 무게로 떠 있었고, 누르는 순간 섹션이 열리며 조회가 나가
// 무엇이 켜져 있는지와 무엇이 아직 안 돌았는지가 화면에 없었다. 지금은 켜짐/꺼짐이 보이고, 조회는
// "아직 결과가 없거나 SQL이 바뀌었을 때"만 나간다 — 다시 켜면 보관된 결과를 조회 없이 보여준다.
const DETAIL_RUN = {
  explain: () => runExplain(),
  schema: () => runReferencedSchema(),
  ai: () => runAiAnalysis(),
  advisor: () => runIndexAdvisor(),
  antipattern: () => runAntiPatterns(),
};
const DETAIL_VIEWS = {
  explain: "plan-section", schema: "schema-section", ai: "ai-section",
  advisor: "advisor-section", antipattern: "antipattern-section",
};
// 어느 SQL의 결과인지 — 토글이 다시 조회할지 판단하는 근거다
const detailView = { open: {}, sql: {} };

function detailSql() { return $("#detail-sql").value.trim(); }

/**
 * 조회가 끝났다 — 그 결과가 어느 SQL의 것인지 기록하고 "다시 조회"를 보인다.
 * 토글이 다시 조회할지 판단하는 근거가 이 기록이고, 한 번도 조회하지 않은 섹션에 "다시 조회"가
 * 떠 있으면 무엇을 다시 조회하는지 알 수 없다(B3 2차).
 */
function markDetailFresh(key) {
  detailView.sql[key] = detailSql();
  const btn = document.querySelector(`[data-refresh="${key}"]`);
  if (btn) btn.hidden = false;
}

/** 토글 상태를 화면에 반영한다(섹션 열기/닫기 + aria-pressed) */
function setDetailView(key, on) {
  detailView.open[key] = on;
  $("#btn-" + key).setAttribute("aria-pressed", String(on));
  $("#" + DETAIL_VIEWS[key]).hidden = !on;
}

function toggleDetailView(key) {
  const on = !detailView.open[key];
  setDetailView(key, on);
  if (!on) return;
  const stale = detailView.sql[key] !== detailSql();
  // 인덱스 제안만 사람의 입력(후보 컬럼)이 필요하다 — 토글이 대신 실행하지 않고 입력칸으로 보낸다.
  // 대신 SQL이 바뀌었으면 옛 결과는 그 SQL의 것이 아니므로 버린다
  if (key === "advisor") {
    if (stale) resetAdvisor();
    $("#advisor-columns").focus();
    return;
  }
  if (stale) DETAIL_RUN[key]();
}

/** "다시 조회" — 보관된 결과를 버리고 같은 SQL로 새로 조회한다(같은 문장이라도 통계는 변한다) */
function refreshDetailView(key) {
  detailView.sql[key] = "";
  DETAIL_RUN[key]();
}

function resetAdvisor() {
  $("#advisor-columns").value = "";
  $("#advisor-result").innerHTML = "";
  detailView.sql.advisor = "";
}

// 더보기 메뉴 — 드물게 쓰는 둘(심층 진단·문의)을 접어 둔다. 항목은 실행하지 않고 섹션만 연다:
// 전에는 누르는 즉시 쿼리를 실제로 실행하거나 문의를 보냈고, 비고 칸은 보낸 뒤에야 나타났다
function openDetailMore() {
  $("#detail-more-menu").hidden = false;
  $("#btn-detail-more").setAttribute("aria-expanded", "true");
}
function closeDetailMore(refocus = false) {
  const menu = $("#detail-more-menu");
  if (!menu || menu.hidden) return;
  menu.hidden = true;
  $("#btn-detail-more").setAttribute("aria-expanded", "false");
  if (refocus) $("#btn-detail-more").focus();
}

function openDetailSection(id) {
  closeDetailMore();
  $(id).hidden = false;
  $(id).scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function bindRowClicks(rows) {
  $("#top-table").querySelectorAll("tbody tr").forEach((tr) => {
    tr.addEventListener("click", () => {
      document.querySelectorAll("#top-table tbody tr").forEach((r) => r.classList.remove("selected"));
      tr.classList.add("selected");
      openDetail(rows[tr.dataset.idx], tr);
    });
  });
}

// 상세 패널을 클릭한 행 바로 아래에 끼워 넣는다(레퍼런스처럼 인라인 확장 — 맨 아래로 튀지 않게)
function placeDetailUnder(tr) {
  const detail = $("#query-detail");
  let host = $("#top-table").querySelector("tbody tr.detail-host");
  if (!host) {
    host = document.createElement("tr");
    host.className = "detail-host";
    host.innerHTML = '<td class="detail-cell"></td>';
  }
  host.firstElementChild.colSpan = tr.children.length;
  // 상세를 폭 맞춤 래퍼 안에 넣는다 — 146절의 width:0 규칙만으로는 표가 화면보다 넓을 때 상세가 표 폭만큼 넓어진다(B6fix)
  let wrap = host.firstElementChild.querySelector(".row-detail-fit");
  if (!wrap) {
    wrap = document.createElement("div");
    wrap.className = "row-detail-fit";
    host.firstElementChild.appendChild(wrap);
  }
  wrap.appendChild(detail);
  tr.after(host);
  fitRowDetails();
}

/** 표에서 가장 가까운 가로 스크롤 상자 — 상세 폭의 기준이 되는 '보이는 폭'을 가진 조상 */
function scrollBoxOf(el) {
  for (let n = el.parentElement; n; n = n.parentElement) {
    const ox = getComputedStyle(n).overflowX;
    if (ox === "auto" || ox === "scroll") return n;
  }
  return null;
}

/**
 * 표 행 안에 끼운 상세의 폭을 스크롤 상자의 **보이는 폭**에 맞춘다.
 *
 * 146절은 셀 안 상세를 `width: 0; min-width: calc(100% - 24px)`로 표 폭에 맞췄고, 표가 화면에 들어오면 그게 정답이다.
 * 표가 화면보다 넓으면(390px) `100%`가 곧 표 전체 폭이라 상세 오른쪽이 스크롤 밖으로 나가고, 더보기·토글 오른쪽을
 * 쓸 수 없게 된다(B6fix — 페이지 넘침은 0이었지만 사람은 쓸 수 없었다). 래퍼를 왼쪽 고정(sticky)으로 두고
 * 폭을 상자의 clientWidth로 준다 — 표를 옆으로 밀어도 상세는 제자리에서 보이는 폭을 다 쓴다.
 */
function fitRowDetails(root = document) {
  root.querySelectorAll(".row-detail-fit").forEach((wrap) => {
    const box = scrollBoxOf(wrap);
    if (!box) return;
    wrap.style.width = box.clientWidth + "px";
  });
}

/** 창·사이드바 폭이 바뀌면 상세 폭도 따라간다(상자를 관찰한다 — 표 내용이 아니라 상자 폭이 기준이다) */
function watchRowDetails() {
  const obs = new ResizeObserver(() => fitRowDetails());
  document.querySelectorAll(".table-scroll, #score-result, #freshness-result, #command-metrics")
    .forEach((box) => obs.observe(box));
  window.addEventListener("resize", () => fitRowDetails());
}

function openDetail(query, tr) {
  state.currentQuery = query;
  $("#btn-to-workbench").hidden = !can("WORKBENCH");
  $("#query-detail").hidden = false;
  if (tr) placeDetailUnder(tr);
  // SQL ID는 64자(MySQL digest)나 20자리(PG queryid)라 통째로 보이면 값이 이상해 보인다(사용자 지적).
  // 짧게 보이고, 전체 값은 title에 남기고 복사 버튼이 그 값을 집어 간다 — 줄이되 숨기지 않는다
  const qid = String(query.queryId ?? "");
  $("#detail-qid").textContent = qid ? shortQueryId(qid) : "—";
  $("#detail-qid").title = qid;
  $("#btn-copy-qid").disabled = !qid;
  // 인덱스 제안은 PostgreSQL의 HypoPG 전용이다 — 눌러서 "지원하지 않습니다"를 읽게 하지 않고 감춘다(159절)
  $("#btn-advisor").hidden = state.instance?.type !== "POSTGRESQL";
  const formatted = formatSql(query.queryText);
  $("#detail-sql").value = formatted;
  $("#detail-sql").rows = Math.min(24, Math.max(5, formatted.split("\n").length + 1)); // 포매팅 줄 수에 맞춰 높이 자동
  updateSqlHl(); // SQL 구문 강조 레이어 갱신

  // 쿼리를 새로 열면 보기 토글도 전부 꺼짐으로 되돌리고, 직전 쿼리의 결과는 첨부 대상이 아니다 — 비운다.
  // "다시 조회"도 함께 감춘다: 결과가 없는 섹션에 그 버튼만 떠 있으면 무엇을 다시 조회하는지 알 수 없다
  Object.keys(DETAIL_VIEWS).forEach((key) => setDetailView(key, false));
  Object.keys(DETAIL_RUN).forEach((key) => { detailView.sql[key] = ""; });
  $("#query-detail").querySelectorAll(".section-refresh").forEach((b) => { b.hidden = true; });
  $("#schema-result").innerHTML = "";
  $("#advisor-columns").value = "";
  $("#advisor-result").innerHTML = "";
  $("#antipattern-result").innerHTML = "";
  $("#deep-section").hidden = true;
  $("#deep-result").innerHTML = "";
  $("#inquiry-section").hidden = true;
  $("#inquiry-note").value = "";
  $("#inquiry-result").innerHTML = "";
  closeDetailMore();
  state.lastPlan = null;
  state.lastFindings = [];
  state.lastAi = null;
  $("#query-detail").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function closeDetail() {
  const detail = $("#query-detail");
  detail.hidden = true;
  closeDetailMore();
  // 인라인 호스트 행에서 빼내 원위치(테이블 밖 tab-top)로 되돌린다 — 표를 다시 그려도 상세 엘리먼트가 살아남게
  $("#tab-top").appendChild(detail);
  const host = $("#top-table").querySelector("tbody tr.detail-host");
  if (host) host.remove();
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
    // "다시 조회"는 결과를 버리고 새로 조회하는 것이다 — 옛 계획이 남아 있으면 새 결과와 구분되지 않는다
    $("#detail-plan").innerHTML = "";
    $("#detail-findings").innerHTML = "";
    try {
      data = await api(`/api/instances/${state.instance.id}/explain`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      $("#detail-plan").textContent = `실행 실패: ${e.message}`;
      $("#detail-findings").innerHTML = "";
      return;
    }
    fillPlan($("#detail-plan"), data);
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("") ||
      '<div class="muted">규칙 기반 지적 없음 — 비효율 신호가 발견되지 않았습니다.</div>';
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
  } finally {
    btn.classList.remove("loading");
    markDetailFresh("explain");   // 실패해도 "이 SQL로 조회했다" — 다시 켤 때 같은 요청을 반복하지 않는다
  }
}

// 안티패턴 신호 (158절) — 느림의 크기가 아니라 성질. 축은 5기종 공통이지만 원천 지표 이름은 기종이 답한다.
// 값이 없는 축은 0으로 그리지 않는다 — 그 기종에 그 카운터가 없다는 사유를 그대로 보인다.
async function runAntiPatterns() {
  const btn = $("#btn-antipattern");
  btn.classList.add("loading");
  $("#antipattern-section").hidden = false;
  const box = $("#antipattern-result");
  box.innerHTML = '<div class="muted">안티패턴 신호 조회 중...</div>';
  try {
    const rows = await api(`/api/instances/${state.instance.id}/query-anti-patterns?limit=20`);
    box.innerHTML = renderAntiPatterns(rows);
  } catch (e) {
    box.innerHTML = `<div class="finding-item">조회 실패: ${esc(e.message)}</div>`;
  } finally {
    btn.classList.remove("loading");
    markDetailFresh("antipattern");
  }
}

// 정규화 쿼리 식별자를 사람이 읽을 길이로 줄인다. PostgreSQL queryid는 부호 있는 64비트라 음수로 찍히는데,
// 화면에서 20자리 숫자는 서로 구분이 안 되고 "왜 마이너스인가"라는 오해만 남긴다(162절 지적).
// 전체 값은 title로 남겨 복사·대조가 가능하게 둔다 — 줄이되 숨기지 않는다.
function shortQueryId(id) {
  const s = String(id ?? "");
  if (!s) return "—";
  if (s.length <= 12) return s;
  const neg = s.startsWith("-") || s.startsWith("−");
  const digits = neg ? s.slice(1) : s;
  return `${neg ? "n" : ""}${digits.slice(0, 6)}…${digits.slice(-4)}`;
}

function renderAntiPatterns(rows) {
  if (!rows.length) return '<div class="muted">신호가 없습니다.</div>';
  if (rows.length === 1 && rows[0].source === "UNSUPPORTED") {
    return `<div class="finding-item muted">판정 불가: ${esc(rows[0].note ?? "")}</div>`;
  }
  // 지금 보고 있는 쿼리를 먼저 — 통계 뷰의 식별자가 쿼리 상세와 같은 기종에서만 맞아떨어진다.
  // 나머지는 접어 둔다(B3): 20행이 펼쳐져 있으면 지금 쿼리의 신호가 그 벽에 묻힌다
  const current = state.currentQuery ? String(state.currentQuery.queryId) : null;
  const mine = current ? rows.find((q) => String(q.queryId) === current) : null;
  const others = rows.filter((q) => q !== mine).slice(0, 9);
  const head = mine
    ? apRow(mine, true)
    : '<div class="finding-item muted">이 쿼리의 신호는 통계 상위 20개에 없습니다.</div>';
  const tail = others.length
    ? `<div id="ap-others" hidden>${others.map((q) => apRow(q, false)).join("")}</div>
       <button type="button" class="ap-more" data-more="${others.length}" aria-expanded="false" aria-controls="ap-others">다른 쿼리 ${others.length}개 보기</button>`
    : "";
  // 같은 기종이면 note가 모든 행에 같다 — 행마다 반복하지 않고 목록 아래 한 번만 적는다
  return head + tail + (rows[0].note ? `<div class="ap-note muted">${esc(rows[0].note)}</div>` : "");
}

function apRow(q, isCurrent) {
  const axis = (m) => {
    if (!m || m.value == null) return '<b class="ap-none">미확보</b>';
    const v = m.value >= 100 ? fmtNum(m.value, 0) : fmtNum(m.value, 2);
    return `<b>${v}</b> <span class="muted">${esc(m.unit ?? "")}</span>`;
  };
  const src = (m) => (m ? `<div class="ap-src">${esc(m.sourceName ?? "")}</div>` : "");
  return `
    <div class="finding-item ap-row${isCurrent ? " ap-current" : ""}">
      <div class="ap-head"><b title="${esc(String(q.queryId ?? ""))}">${esc(shortQueryId(q.queryId))}</b>
        <span class="muted">실행 ${fmtNum(q.calls, 0)}회</span></div>
      ${q.queryText ? `<div class="ap-text muted">${esc(q.queryText.replace(/\s+/g, " ").slice(0, 120))}</div>` : ""}
      <div class="ap-axes">
        <div><span class="muted">인덱스 없이 훑음</span><span class="ap-val">${axis(q.fullScan)}</span>${src(q.fullScan)}</div>
        <div><span class="muted">디스크로 넘침</span><span class="ap-val">${axis(q.diskSpill)}</span>${src(q.diskSpill)}</div>
        <div><span class="muted">행당 읽은 양</span><span class="ap-val">${axis(q.examinedPerRow)}</span>${src(q.examinedPerRow)}</div>
      </div>
    </div>`;
}

// 관련 테이블 구조 — 쿼리가 참조하는 테이블의 컬럼·인덱스·대략 행수. 문의 시 서버가 자동 첨부하지만,
// 보내기 전에 사이트에서 미리 확인할 수 있게 한다(원본 요청: "그 사이트에서 볼 때도 마찬가지").
async function runReferencedSchema() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-schema");
  btn.classList.add("loading");
  $("#schema-section").hidden = false;
  const box = $("#schema-result");
  box.innerHTML = '<div class="muted">참조 테이블 구조 조회 중...</div>';
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/referenced-schema`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      box.innerHTML = `<div class="finding-item">조회 실패: ${esc(e.message)}</div>`;
      return;
    }
    box.innerHTML = renderReferencedSchema(data);
  } finally {
    btn.classList.remove("loading");
    markDetailFresh("schema");
  }
}

// 펼친 상세가 이 목록의 열(타입·NULL)을 다시 쓴다 — 테이블 상세 API는 열 목록을 주지 않는다
const refSchemaByName = new Map();

function renderReferencedSchema(data) {
  const tables = data.tables ?? [];
  refSchemaByName.clear();
  for (const t of tables) refSchemaByName.set(t.name, t);
  if (!tables.length && !(data.notFound ?? []).length) {
    return '<div class="muted">쿼리에서 참조 테이블을 찾지 못했습니다 (FROM/JOIN 확인).</div>';
  }
  let html = "";
  for (const t of tables) {
    // 행수·크기·인덱스 타입/카디널리티는 tableDetail 원천 — 미확보(-1/null)면 표기 생략(위장 금지)
    const facts = [];
    if (t.rowCountApprox >= 0) facts.push(`≈ ${t.rowCountApprox.toLocaleString()}행`);
    if (t.dataBytes >= 0) facts.push(`데이터 ${fmtBytes(t.dataBytes)}`);
    if (t.indexBytes >= 0) facts.push(`인덱스 ${fmtBytes(t.indexBytes)}`);
    const rows = facts.length ? ` <span class="muted">${facts.join(" · ")}</span>` : "";
    const idx = (t.indexes ?? []).length
      ? (t.indexes.map((i) => {
          const extra = [i.type ? esc(i.type) : "", i.cardinality != null ? `card≈${Number(i.cardinality).toLocaleString()}` : ""].filter(Boolean).join("·");
          return `${esc(i.name)}${i.unique ? "<span class=\"idx-u\">[U]</span>" : ""}(${esc((i.columns ?? []).join(","))})${extra ? ` <span class="muted">${extra}</span>` : ""}`;
        }).join(", "))
      : '<span class="muted">없음</span>';
    // 기본키·외래키 열 표시(151절) — 조인 열이 키를 따르는지가 계획 진단의 재료다
    const pk = new Set((t.primaryKey ?? []).map((c) => c.toLowerCase()));
    const fkCols = new Set((t.foreignKeys ?? []).flatMap((fk) => fk.columns.map((c) => c.toLowerCase())));
    const cols = (t.columns ?? []).map((c) => {
      const key = c.name.toLowerCase();
      const marks = `${pk.has(key) ? '<span class="key-badge pk">PK</span>' : ""}${fkCols.has(key) ? '<span class="key-badge fk">FK</span>' : ""}`;
      return `${esc(c.name)}${marks} <span class="muted">${esc(c.type)}${c.nullable ? "?" : ""}</span>`;
    }).join(", ");
    const fks = (t.foreignKeys ?? []).length
      ? `<div class="schema-cols">fk: ${t.foreignKeys.map((fk) => `${esc(fk.columns.join(","))} → ${esc(fk.refTable)}(${esc(fk.refColumns.join(","))})`).join(", ")}</div>`
      : "";
    html += `<div class="finding-item schema-table"><b>${esc(t.name)}</b>${rows}
      <button class="btn btn-small td-toggle" data-table="${esc(t.name)}">상세 보기</button>
      <div class="schema-idx">idx: ${idx}</div>
      <div class="schema-cols">cols: ${cols}</div>
      ${fks}
      <div class="td-detail" hidden></div></div>`;
  }
  if ((data.notFound ?? []).length) {
    html += `<div class="finding-item muted">구조 미확보: ${esc(data.notFound.join(", "))}${data.truncated ? " (스키마 상한 초과 가능)" : ""}</div>`;
  }
  // 렌더 직후 "상세 보기" 버튼에 아코디언 토글을 건다(테이블별 table-detail 조회)
  queueMicrotask(() => {
    document.querySelectorAll("#schema-result .td-toggle").forEach((btn) => {
      btn.addEventListener("click", () => toggleTableDetail(btn));
    });
  });
  return html;
}

// 테이블 상세 정보 — 워크벤치 "테이블 상세" 탭과 같은 렌더러로 펼친다(151절). 모듈이라 처음 펼칠 때 불러온다
async function toggleTableDetail(btn) {
  const box = btn.parentElement.querySelector(".td-detail");
  if (!box.hidden) { box.hidden = true; btn.textContent = "상세 보기"; return; }
  box.hidden = false; btn.textContent = "접기";
  if (box.dataset.loaded) return;
  box.innerHTML = '<div class="muted">테이블 상세 조회 중...</div>';
  try {
    const [d, { renderTableDetail }] = await Promise.all([
      api(`/api/instances/${state.instance.id}/table-detail`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ table: btn.dataset.table }),
      }),
      import("./workbench/table-detail.js"),
    ]);
    const ref = refSchemaByName.get(btn.dataset.table);
    box.innerHTML = renderTableDetail(d, { columns: ref ? ref.columns : null });
    box.dataset.loaded = "1";
  } catch (e) {
    box.innerHTML = `<div class="finding-item">상세 조회 실패: ${esc(e.message)}</div>`;
  }
}

// AI 분석은 흘려 받는다(143절) — 실행계획은 1초 안에 오는데 AI 답은 수십 초 걸린다. 한 번에 받으면 그동안 계획까지 같이 기다렸다.
// 순서: plan(계획·규칙 지적) -> text(쓰이는 대로) -> result(완성본). 화면에 남기고 문의에 첨부하는 것은 완성본이다
async function runAiAnalysis() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-ai");
  const out = $("#detail-ai"), stage = $("#ai-stage");
  btn.classList.add("loading");
  $("#ai-section").hidden = false;
  out.textContent = "";
  stage.textContent = "실행계획을 조회하는 중";
  const startedAt = Date.now();
  let written = "", firstTextAt = null, result = null;
  try {
    await streamSse(`/api/instances/${state.instance.id}/ai-analysis/stream`, { sql }, (name, data) => {
      if (name === "plan") {
        $("#plan-section").hidden = false;
        fillPlan($("#detail-plan"), data);
        $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
          `<div class="finding-item">${esc(f)}</div>`).join("");
        stage.textContent = `실행계획을 받았습니다(${((Date.now() - startedAt) / 1000).toFixed(1)}초). AI가 판단 기준 문서 위에서 분석하는 중`;
      } else if (name === "text") {
        if (firstTextAt == null) firstTextAt = Date.now();
        written += data.delta;
        out.textContent = stripEmoji(written);
        stage.textContent = "AI가 답을 쓰는 중";
      } else if (name === "result") {
        result = data;
      } else if (name === "error") {
        throw new Error(data.message);
      }
    });
    if (!result) throw new Error("분석 결과가 끝까지 오지 않았습니다(연결 끊김)");
    out.textContent = stripEmoji(result.aiAnalysis) ||
      "AI 분석 비활성화 상태입니다 (ANTHROPIC_API_KEY도 claude CLI도 없음) — 규칙 기반 지적까지만 표시합니다.";
    const first = firstTextAt ? ` · 첫 글자 ${((firstTextAt - startedAt) / 1000).toFixed(1)}초` : "";
    stage.textContent = `완료 ${((Date.now() - startedAt) / 1000).toFixed(1)}초${first}`;
    state.lastPlan = result.plan;
    state.lastFindings = result.findings ?? [];
    state.lastAi = result.aiAnalysis ?? null;
  } catch (e) {
    out.textContent = `실패: ${e.message}`;
    stage.textContent = "";
  } finally {
    btn.classList.remove("loading");
    markDetailFresh("ai");
  }
}

// 인덱스 어드바이저 — 후보 컬럼으로 가상 인덱스를 만들었을 때 플랜 비용이 어떻게 바뀌는지 시뮬레이션.
// PostgreSQL은 HypoPG로 실제 인덱스 없이 before/after 비용을 비교하고, 타 기종은 UNSUPPORTED를 그대로 보여준다.
const ADVISOR_STATUS = {
  ADVISED: { cls: "advised", label: "제안" },
  NO_BENEFIT: { cls: "no-benefit", label: "이득 없음" },
  UNSUPPORTED: { cls: "unsupported", label: "미지원" },
};

async function runIndexAdvisor() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const columns = $("#advisor-columns").value.trim();
  const btn = $("#btn-advisor-run");
  btn.classList.add("loading");
  const result = $("#advisor-result");
  result.innerHTML = '<div class="muted">가상 인덱스로 시뮬레이션 중...</div>';
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/index-advisor`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sql, columns: columns || null }),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">시뮬레이션 실패: ${esc(e.message)}</div>`;
      return;
    }
    const meta = ADVISOR_STATUS[data.status] || { cls: "unsupported", label: data.status };
    let html = `<div class="finding-item"><span class="advisor-status ${meta.cls}">${esc(meta.label)}</span>${esc(data.detail)}</div>`;
    if (data.suggestedIndex) {
      // 제안에서 끝내지 않고 변경 요청으로 잇는다 — 가상 인덱스 결과는 판단 근거로 사유에 싣고, 실제 생성은 승인·드라이런을 거친다
      html += `<div class="finding-item">제안 인덱스: <code>${esc(data.suggestedIndex)}</code>${can("CHANGE_REQUEST")
        ? ' <button id="btn-advisor-ticket" class="btn btn-small">워크벤치에서 변경 요청으로 올리기</button>' : ""}</div>`;
    }
    if (data.beforeCost != null && data.afterCost != null) {
      html += `<div class="finding-item">Total Cost: ${esc(data.beforeCost)} → ${esc(data.afterCost)}</div>`;
    }
    if (data.beforePlan) {
      html += `<h3>변경 전 실행계획</h3><pre class="codeblock">${esc(data.beforePlan)}</pre>`;
    }
    if (data.afterPlan) {
      html += `<h3>가상 인덱스 적용 후 실행계획</h3><pre class="codeblock">${esc(data.afterPlan)}</pre>`;
    }
    result.innerHTML = html;
    $("#btn-advisor-ticket")?.addEventListener("click", () => {
      const cost = data.beforeCost != null && data.afterCost != null ? `, 가상 인덱스 Total Cost ${data.beforeCost} → ${data.afterCost}` : "";
      const qid = state.currentQuery ? ` SQL ID ${state.currentQuery.queryId}` : "";
      handToWorkbench("draft", data.suggestedIndex, `대시보드 인덱스 제안(HypoPG)${qid}${cost}`);
    });
  } finally {
    btn.classList.remove("loading");
    markDetailFresh("advisor");
  }
}

// 심층 원인 진단 (D9) — 실제 실행 계획으로 카디널리티 괴리·근본원인을 짚는다.
// explain(추정)과 달리 쿼리를 실제 실행하므로 운영자·관리자 전용(서버가 인가). 파라미터 자리는 실제 값이어야 한다.
async function runDeepDiagnose() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  // 이 진단만 쿼리를 실제로 실행한다 — 통계의 정규화 텍스트($1·?)를 그대로 보내면 대상 DB가 바인드 단계에서
  // 거부하고("bind message supplies 0 parameters"), 화면에는 errorId만 남아 서버 장애처럼 보인다(162절).
  // 보내기 전에 여기서 막고 무엇을 고쳐야 하는지 적는다.
  const placeholder = sql.match(/\$\d+|(?<![\w'"])\?(?![\w'"])|:\w+/);
  if (placeholder) {
    $("#deep-section").hidden = false;
    $("#deep-result").innerHTML = `<div class="finding-item">파라미터 자리가 남아 있습니다 — `
      + `<code>${esc(placeholder[0])}</code>`
      + `<div class="muted">이 진단은 쿼리를 대상 DB에서 실제로 실행하므로 자리표시자를 그대로 보낼 수 없습니다. `
      + `위 SQL 편집칸에서 실제 값으로 바꾼 뒤 다시 눌러 주세요(추정만 하는 "실행계획 보기"는 그대로 됩니다).</div></div>`;
    return;
  }
  const btn = $("#btn-deep-run");
  btn.classList.add("loading");
  $("#deep-section").hidden = false;
  const result = $("#deep-result");
  result.innerHTML = '<div class="muted">실제 실행 계획으로 진단 중... (쿼리를 실제 실행 — 타임아웃 적용)</div>';
  try {
    let data;
    try {
      data = await api(`/api/instances/${state.instance.id}/deep-diagnose`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      result.innerHTML = `<div class="finding-item">진단 실패: ${esc(e.message)}</div>`;
      return;
    }
    // 표시 순서 원칙(외부 리뷰 반영): 근본원인이 있으면 "원인 -> 증상" 순으로.
    // 괴리(증상)가 첫 카드면 사용자가 "통계 갱신(ANALYZE)"이라는 엉뚱한 처방으로 빠질 수 있다.
    // 원인을 못 찾았을 때만 괴리가 헤드라인이 된다 — 그때는 그게 유일한 단서라서다.
    const causes = data.rootCauses ?? [];
    let html = "";

    // before/after 검증 루프: 수정안 재진단이면 이전 결과와의 비교 스트립을 먼저 보여준다
    if (state.deepBefore && state.deepBefore.sql !== sql) {
      const b = state.deepBefore;
      const now = data.worstGap ? `괴리 ${fmtNum(data.worstGap.ratio)}배` : "괴리 없음";
      html += `<div class="finding-item"><strong>수정 전 -> 후</strong> — `
        + `${esc(b.summary)} -> ${esc(now)}, 근본원인 ${b.causeCount}건 -> ${causes.length}건</div>`;
    }
    state.deepBefore = null;

    if (causes.length) {
      html += causes.map((c) => {
        let card = `<div class="finding-item"><span class="advisor-status unsupported">근본 원인 — ${esc(c.cause)}</span>`
          + `<div class="advisor-finding-detail">신호: ${esc(c.signal)}</div>`
          + `<div class="advisor-finding-reco">${esc(c.detail)}</div>`;
        if (c.suggestedSql) {
          card += `<button class="btn btn-small deep-retry" data-sql="${esc(c.suggestedSql)}">수정안으로 재진단 (before/after)</button>`;
        }
        return card + `</div>`;
      }).join("");
      if (data.worstGap) {
        const g = data.worstGap;
        html += `<div class="finding-item"><strong>증상 — 카디널리티 오추정</strong> (위 원인의 부산물: 변형된 술어에는 `
          + `인덱스 통계를 못 써 추정이 어긋남 — 통계 갱신으로는 안 풀린다) — ${esc(g.node)}: `
          + `추정 ${fmtNum(g.estimatedRows, 0)}행 vs 실제 ${fmtNum(g.actualRows, 0)}행 (약 ${fmtNum(g.ratio)}배 괴리)</div>`;
      }
    } else {
      if (data.worstGap) {
        const g = data.worstGap;
        html += `<div class="finding-item"><strong>카디널리티 오추정</strong> — ${esc(g.node)}: `
          + `추정 ${fmtNum(g.estimatedRows, 0)}행 vs 실제 ${fmtNum(g.actualRows, 0)}행 `
          + `(약 ${fmtNum(g.ratio)}배 괴리)</div>`;
      } else {
        html += `<div class="muted">추정·실제 행수 괴리(10배+) 지점 없음 — 카디널리티는 대체로 맞음.</div>`;
      }
      html += `<div class="muted">근본원인 규칙 매칭 없음 — 형변환·컬럼함수·선두 누락 신호가 발견되지 않음.</div>`;
    }
    if ((data.notes ?? []).length) {
      html += `<div class="advisor-note muted">${data.notes.map(esc).join(" · ")}</div>`;
    }
    html += `<h3>실제 실행 계획</h3><pre class="codeblock">${planHtml(data.plan)}</pre>`;
    result.innerHTML = html;
    // 수정안 원클릭 재진단 — 이전 결과 요약을 담아두고 SQL을 바꿔 다시 돌린다
    result.querySelectorAll(".deep-retry").forEach((b) => b.addEventListener("click", () => {
      state.deepBefore = {
        sql,
        summary: data.worstGap ? `괴리 ${fmtNum(data.worstGap.ratio)}배` : "괴리 없음",
        causeCount: causes.length,
      };
      $("#detail-sql").value = b.dataset.sql;
      runDeepDiagnose();
    }));
  } finally { btn.classList.remove("loading"); }
}

// 현재 상세 패널의 쿼리·실행계획·규칙 지적·AI 분석을 모아 DB팀에 문의(웹훅 push).
// 실행계획/AI를 안 돌렸어도 쿼리만으로 문의할 수 있게 plan/findings/ai는 있으면 첨부한다.
async function runInquiry() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-inquiry-send");
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
// Slow 시각 — 기종별 원문이 제각각(ISO·공백 구분·Mongo 원문 문자열)이라, 파싱 가능한 것만
// 브라우저 로컬로 변환하고(툴팁에 UTC 원문 유지) 못 읽는 원문은 그대로 "(UTC)"로 정직 표기한다.
function fmtSlowTime(s) {
  if (!s) return "-";
  const raw = String(s).trim();
  const isoLike = raw.replace(" ", "T");
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(isoLike)) return `${esc(raw)} <span class="muted">(UTC)</span>`;
  const d = parseApiTime(isoLike);
  if (isNaN(d)) return `${esc(raw)} <span class="muted">(UTC)</span>`;
  const p = (n) => String(n).padStart(2, "0");
  const local = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  return `<span title="UTC 원문: ${esc(raw)}">${esc(local)}</span>`;
}

async function loadSlow() {
  const table = $("#slow-table");
  // 기종별로 확보 가능한 필드가 달라 미확보는 "—"로 표기(MySQL: User@host·Lock·Rows_sent, Mongo: Plan)
  table.querySelector("thead").innerHTML = `
    <tr><th>Captured <span class="muted" title="브라우저 시간대로 변환 표시 — 원문(UTC)은 툴팁">(로컬)</span></th><th>User@host</th><th class="num">Query(ms)</th><th class="num">Lock(ms)</th>
        <th class="num">Rows_sent</th><th class="num">Rows_examined</th><th>Plan</th><th>Query</th></tr>`;
  const dash = (v) => (v == null || v < 0) ? '<span class="muted">—</span>' : null;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/slow-queries?limit=20`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((q) => `
      <tr>
        <td class="num">${fmtSlowTime(q.capturedAt)}</td>
        <td>${q.userHost ? esc(q.userHost) : '<span class="muted">—</span>'}</td>
        <td class="num">${fmtNum(q.elapsedMs)}</td>
        <td class="num">${dash(q.lockMs) ?? fmtNum(q.lockMs)}</td>
        <td class="num">${dash(q.rowsSent) ?? fmtNum(q.rowsSent, 0)}</td>
        <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
        <td>${q.planSummary ? `<span class="plan-badge ${/COLLSCAN/i.test(q.planSummary) ? "plan-bad" : "plan-ok"}">${esc(q.planSummary)}</span>` : '<span class="muted">—</span>'}</td>
        <td class="qtext" data-sql-tip="${esc(q.queryText)}" tabindex="0" aria-describedby="sql-tip">${queryTextHtml(q.queryText)}</td>
      </tr>`).join("") : '<tr><td colspan="8" class="muted">슬로우 쿼리가 없습니다.</td></tr>';
  } catch (e) {
    // 형제 로더(top·sessions·latency·partitions·deadlocks)와 같은 모양 — 실패를 이 카드에 적고 끝낸다.
    // 여기서 던지면 selectInstance의 Promise.all이 깨지고, 그 약속에 걸린 딥링크(?aiop=·compareAt)까지 함께 사라진다(170절 9번)
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="8" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
  // Mongo 보존 창 정직 표기 — system.profile은 순환(capped) 컬렉션이라 오래된 항목이 덮어써진다.
  // 로그 파일 파싱 기반 도구와 보존 범위가 다름을 숨기지 않는다. 조회가 실패해도 이 문구는 지금 대상의 것이라
  // 성공 경로에만 두면 다른 대상의 설명이 남는다
  const note = $("#slow-source-note");
  if (note) {
    note.textContent = state.instance.type === "MONGODB"
      ? "MongoDB는 system.profile(순환 컬렉션) 기반 — 컬렉션 크기만큼만 보존되며 오래된 항목은 덮어써집니다(로그 파일 파싱 방식과 보존 창이 다름)."
      : "";
  }
}

// 백업/PITR 카드 (Phase 2) — 이력(타입·상태·검증)과 복원 가능 창·문안을 보여준다.
// UNSUPPORTED는 실패가 아니라 "기종이 못 하는 것" — 색으로 구분해 위장하지 않는다.
async function loadBackupInfo() {
  const id = state.instance.id;
  try {
    const [runs, pitr] = await Promise.all([
      api(`/api/instances/${id}/backup-runs`),
      api(`/api/instances/${id}/pitr-window`),
    ]);
    $("#pitr-window").className = "pitr-window";
    $("#pitr-window").innerHTML = pitr.available
      ? `<span class="pitr-ok">복원 가능 창</span> ${esc(String(pitr.fullAt).replace("T", " "))} ~ ${
          pitr.lastLogAt ? esc(String(pitr.lastLogAt).replace("T", " ")) : "(FULL 시점만)"} · 로그 ${pitr.logCount}개
         <div class="muted">${esc(pitr.note ?? "")}</div>`
      : `<span class="pitr-none">시점 복구 불가</span> <span class="muted">${esc(pitr.note ?? "")}</span>`;
    $("#pitr-guide").textContent = pitr.restoreGuide ?? "(문안 없음)";
    const table = $("#backup-table");
    table.querySelector("thead").innerHTML =
      `<tr><th>시각 (UTC)</th><th>타입</th><th>상태</th><th>검증</th><th>산출물/사유</th></tr>`;
    const badge = (s) => `<span class="bk-badge bk-${esc(s)}">${esc(s)}</span>`;
    table.querySelector("tbody").innerHTML = runs.length ? runs.map((r) => `
      <tr>
        <td class="num">${esc(String(r.startedAt).replace("T", " ").slice(0, 19))}</td>
        <td>${r.backupType ? esc(r.backupType) : '<span class="muted">—</span>'}</td>
        <td>${badge(r.status)}</td>
        <td>${r.verifyStatus ? esc(r.verifyStatus) : '<span class="muted">—</span>'}</td>
        <td class="qtext" title="${esc(r.detail ?? "")}">${esc((r.detail ?? "").slice(0, 90))}</td>
      </tr>`).join("") : '<tr><td colspan="5" class="muted">백업 이력이 없습니다.</td></tr>';
  } catch (e) {
    $("#pitr-window").textContent = `조회 실패: ${e.message}`;
  }
}

// MCP 카드 — 도구 목록을 MCP 코어의 tools/list에서 받아와 그린다.
// 하드코딩하지 않는 이유: 이 목록이 곧 "MCP 코어가 이 도구들을 내놓는다"의 증거가 되기 때문.
// /mcp는 Bearer 전용 체인(91절)이라 콘솔 세션으로 부르면 401이어서 카드가 안내 문구만 보였다 —
// 같은 코어의 목록을 세션 경로로 받는다(132절).
async function loadMcpTools() {
  const box = $("#mcp-tools");
  const toggle = $("#btn-mcp-tools");
  try {
    const tools = await api("/api/mcp/tools");
    box.classList.remove("muted");
    box.innerHTML = tools.map((t) => `
      <button type="button" class="mcp-tool" aria-expanded="false"><b>${esc(t.name)}</b><p>${esc(t.description)}</p></button>`).join("");
    toggle.dataset.label = `제공 도구 ${tools.length}개 보기`;
    toggle.textContent = toggle.dataset.label;
  } catch (e) {
    box.classList.remove("muted");
    box.textContent = `도구 목록 조회 실패: ${e.message}`;
    toggle.textContent = toggle.dataset.label = "제공 도구 보기";
  }
}

// 제공 도구 접기/펴기 (B4) — 목록이 길어 카드가 화면을 다 먹었다. 항목을 누르면 그 항목의 설명만 펼쳐진다
// (툴팁에 기대지 않는다: 설명이 길고, 키보드만으로도 읽을 수 있어야 한다)
function setupMcpCard() {
  const toggle = $("#btn-mcp-tools");
  const box = $("#mcp-tools");
  toggle.addEventListener("click", () => {
    const open = toggle.getAttribute("aria-expanded") === "true";
    toggle.setAttribute("aria-expanded", String(!open));
    box.hidden = open;
    toggle.textContent = open ? (toggle.dataset.label || "제공 도구 보기") : "접기";
  });
  box.addEventListener("click", (e) => {
    const item = e.target.closest(".mcp-tool");
    if (item) item.setAttribute("aria-expanded", String(item.getAttribute("aria-expanded") !== "true"));
  });
}

// MCP 등록 명령 — ADMIN이면 서비스 토큰을 받아 실제 명령을 완성한다 (A1)
// 능력을 먼저 본다: 토큰 발급은 ADMIN 전용이라 다른 역할이 부르면 403이다. 눌러서 403을 받는 화면을
// 만들지 않는다는 규칙(loadMe 주석)은 사람이 누르는 버튼만이 아니라 화면이 스스로 거는 호출에도 같이 적용된다.
async function loadMcpCommand() {
  if (!can("PLATFORM_ADMIN")) return; // 비관리자는 카드에 적힌 OAuth 등록 안내를 그대로 쓴다
  try {
    const { token } = await api("/api/security/mcp-token");
    $("#mcp-cmd-http").textContent =
      `claude mcp add --transport http dbtower http://localhost:8080/mcp --header "Authorization: Bearer ${token}"`;
    // 명령에 토큰이 들어간 순간 보조 줄도 그 사실을 말해야 한다(공유 금지)
    $("#mcp-http-note").textContent = "서비스 토큰이 들어 있습니다. 공유하지 마세요.";
  } catch { /* ADMIN이 아니면 — 헤더 없는 등록(OAuth 브라우저 로그인) 안내를 유지 */ }
}

// 대시보드에서 워크벤치로 넘기기. SQL은 URL에 싣지 않는다 — 정규화 쿼리 텍스트가 수 KB면 인코딩 후 요청 줄 상한(8KB)을 넘어
// 400이 난다. 같은 브라우저 localStorage에 한 번 쓰고 워크벤치가 읽자마자 지운다(새 탭에서도 같은 출처라 보인다)
const HANDOFF_PREFIX = "dbtower.handoff.";
function handToWorkbench(kind, sql, reason = "") {
  if (!state.instance || !sql) return;
  const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  try {
    // 워크벤치를 열지 않고 버려진 넘김이 쌓이지 않게 10분 지난 것은 지운다
    Object.keys(localStorage).filter((k) => k.startsWith(HANDOFF_PREFIX)).forEach((k) => {
      try { if (Date.now() - JSON.parse(localStorage.getItem(k)).at > 10 * 60000) localStorage.removeItem(k); } catch { localStorage.removeItem(k); }
    });
    localStorage.setItem(HANDOFF_PREFIX + id, JSON.stringify({ kind, sql, reason, at: Date.now() }));
  } catch {
    setInstanceNotice("브라우저 저장소를 쓸 수 없어 워크벤치로 넘기지 못했습니다. SQL을 복사해 워크벤치에 붙여 넣으세요.");
    return;
  }
  // 같은 페이지의 워크벤치 모드로 넘긴다(149절) — 전에는 새 탭에 페이지를 한 벌 더 띄웠다
  setMode("workbench", { instance: state.instance.id, handoff: id });
}

// 사용자·역할 카드(ADMIN). 역할은 인증 시 권한에 실리므로 바꾼 역할은 그 사용자의 다음 로그인부터 적용된다.
//
// select를 바꾸는 즉시 PATCH하지 않는다(B4): 되돌릴 수 없는 요청이 스크롤·오조작으로 나가도 화면에는 흔적이 남지 않는다.
// 바꾼 행에만 "적용/취소"가 나타나고 적용을 눌러야 나간다. 취소는 목록을 다시 그려 원래 값으로 되돌린다.
const USER_NAME_RE = /^[A-Za-z0-9._-]{3,50}$/;
const USER_PASSWORD_MIN = 12;

function setUsersMsg(text, isError) {
  const msg = $("#users-msg");
  msg.textContent = text;
  msg.classList.toggle("is-error", !!isError);   // 실패는 채팅 오류와 같은 빨강 계열
}

async function loadUsers() {
  const tbody = $("#users-table tbody");
  let users;
  try {
    users = await api("/api/security/users");
  } catch (e) {
    tbody.innerHTML = `<tr><td colspan="4" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
    return;
  }
  // select의 영문 괄호는 뺀다 — 화면에는 역할 이름만(value는 그대로)
  const options = (role) => Object.keys(ROLE_LABEL).map((r) =>
    `<option value="${r}"${r === role ? " selected" : ""}>${esc(ROLE_LABEL[r])}</option>`).join("");
  tbody.innerHTML = users.map((u) => `<tr data-user="${esc(u.username)}" data-role="${esc(u.role)}">
      <td>${esc(u.username)}</td>
      <td><select data-user-role="${esc(u.username)}" aria-label="${esc(u.username)} 역할">${options(u.role)}</select></td>
      <td>${esc(u.teamLabel ?? "전역")}</td>
      <td><span class="user-role-actions" hidden>
        <span class="user-role-warn" hidden>내 관리자 권한이 없어집니다</span>
        <button type="button" class="btn btn-primary btn-small" data-role-apply>적용</button>
        <button type="button" class="btn btn-small" data-role-cancel>취소</button>
      </span></td>
    </tr>`).join("");
  tbody.querySelectorAll("[data-user-role]").forEach((sel) => {
    sel.addEventListener("change", () => markRoleDirty(sel));
  });
}

/** select가 원래 값과 달라졌을 때만 그 행에 적용·취소를 보인다 */
function markRoleDirty(sel) {
  const tr = sel.closest("tr");
  const changed = sel.value !== tr.dataset.role;
  tr.querySelector(".user-role-actions").hidden = !changed;
  // 자기 자신을 ADMIN에서 내리는 경우를 화면에서 먼저 알린다. 서버는 "마지막 ADMIN"만 막으므로
  // (둘 이상이면 자기 강등이 실제로 된다) 이 경고가 그 자리를 메운다
  const self = sel.dataset.userRole === state.username;
  tr.querySelector(".user-role-warn").hidden =
    !(changed && self && tr.dataset.role === "ADMIN" && sel.value !== "ADMIN");
  return changed;
}

/**
 * 취소 — 그 행을 화면이 기억하는 값(data-role)으로 되돌린다.
 *
 * 서버를 다시 부르지 않는다: 취소는 보낸 적 없는 요청을 무르는 일이라 돌아올 응답이 없다.
 * 목록을 다시 받아 그리는 방식이면 화면이 서버 왕복에 묶여, 대상 조회가 몰려 연결이 밀릴 때
 * (브라우저는 호스트당 연결이 여섯이다) 취소를 눌러도 한참 동안 아무 일도 안 일어난 것처럼 보인다.
 */
function cancelRoleChange(tr) {
  const sel = tr.querySelector("[data-user-role]");
  if (sel) sel.value = tr.dataset.role;
  tr.querySelector(".user-role-actions").hidden = true;
  tr.querySelector(".user-role-warn").hidden = true;
}

async function applyUserRole(sel) {
  const username = sel.dataset.userRole;
  try {
    const r = await api(`/api/security/users/${encodeURIComponent(username)}/role`, {
      method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ role: sel.value }),
    });
    setUsersMsg(`${username}의 역할을 ${ROLE_LABEL[r.role] ?? r.role}(으)로 바꿨습니다. 다음 로그인부터 적용됩니다.`, false);
  } catch (e) {
    setUsersMsg(`역할을 바꾸지 못했습니다: ${e.message}`, true);
  }
  loadUsers();
}

/** 이름·비밀번호가 서버 조건을 채우기 전에는 만들기 버튼을 누를 수 없다(눌러서 400을 받지 않는다) */
function syncUserCreateButton() {
  const name = $("#user-new-name").value.trim();
  const password = $("#user-new-password").value;
  $("#btn-user-create").disabled = !USER_NAME_RE.test(name) || password.length < USER_PASSWORD_MIN;
}

function setupUsersCard() {
  const tbody = $("#users-table tbody");
  // 목록을 다시 그려도 살아남게 tbody에 위임한다(innerHTML 교체는 tbody 자신을 갈지 않는다)
  tbody.addEventListener("click", (e) => {
    const tr = e.target.closest("tr");
    if (!tr) return;
    if (e.target.closest("[data-role-cancel]")) { cancelRoleChange(tr); return; }
    if (e.target.closest("[data-role-apply]")) applyUserRole(tr.querySelector("[data-user-role]"));
  });
  ["user-new-name", "user-new-password"].forEach((id) =>
    $(`#${id}`).addEventListener("input", syncUserCreateButton));
  // 역할 고르기도 다른 화면과 같은 드롭다운으로(B6) — 이 파일의 $는 querySelector라 id에는 #을 붙인다
  enhanceSelect($("#user-new-role"));
  syncUserCreateButton();
}

async function createUser() {
  const body = { username: $("#user-new-name").value.trim(), password: $("#user-new-password").value, role: $("#user-new-role").value };
  try {
    await api("/api/security/users", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    $("#user-new-name").value = "";
    $("#user-new-password").value = "";
    setUsersMsg(`${body.username} 계정을 만들었습니다(${ROLE_LABEL[body.role]}).`, false);
    syncUserCreateButton();
    loadUsers();
  } catch (e) {
    setUsersMsg(`만들지 못했습니다: ${e.message}`, true);
  }
}

// 상단 사용자 표시 + 로그아웃
async function loadMe() {
  try {
    const me = await api("/api/me");
    state.role = me.role;
    state.username = me.username;
    state.caps = new Set(me.capabilities || []);
    $("#user-chip").innerHTML =
      `${esc(me.username)}<span class="role-badge" title="${esc(me.role)}">${esc(ROLE_LABEL[me.role] || me.role)}</span>`;
    // 역할에 없는 입구는 감춘다. 관제만 보는 사람에게 워크벤치·변경 요청 입력을 보여주면 눌러서 403을 받는 화면이 된다
    $("#nav-workbench").hidden = !can("WORKBENCH");
    $("#review-submit").hidden = !can("CHANGE_REQUEST");
    $("#users-card").hidden = !can("PLATFORM_ADMIN");
    if (can("PLATFORM_ADMIN")) loadUsers();
  } catch { /* 401이면 api()가 로그인으로 보낸다 */ }
  $("#logout-btn")?.addEventListener("click", async () => {
    await fetch("/logout", { method: "POST", headers: { "X-XSRF-TOKEN": csrfToken() } });
    location.href = "/login.html";
  });
}

function setupCopyButtons() {
  document.querySelectorAll("[data-copy]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      // code 안에는 명령만 있다(B4) — 괄호 설명을 함께 복사하지 않으려고 잘라내던 코드는 필요 없어졌다
      const text = $(`#${btn.dataset.copy}`).textContent.trim();
      try {
        await navigator.clipboard.writeText(text);
        const old = btn.textContent;
        btn.textContent = "복사됨";
        setTimeout(() => { btn.textContent = old; }, 1200);
      } catch { /* http 컨텍스트 등 클립보드 불가 환경 — 무시 */ }
    });
  });
}

// 지연 값과 "왜 값이 없는가"를 구분해 표기한다 — 예전 응답은 -1을 실어서 미지원과 복제 단절이
// 화면에서 같아 보였다(백엔드 ReplicationState.LagSource 참고).
const LAG_SOURCE_LABEL = {
  MEASURED: null,                                   // 값이 있으므로 배지 없음
  NOT_APPLICABLE: { cls: "muted", label: "복제 미구성" },
  UNSUPPORTED: { cls: "src-unsupported", label: "지연 측정 미지원" },
  UNAVAILABLE: { cls: "verify-FAILED", label: "지연 확인 불가" },
};

const BK_STATUS_LABEL = { FRESH: "신선", STALE: "오래됨", NO_BACKUP: "백업 없음", UNAVAILABLE: "확인 불가" };

// 대상별 운영 종합 (DBRE) — 정체(이름·기종·환경·클러스터)와 상태(헬스·복제·백업·RPO 노출)를
// 한 대상 단위에 모아 선택 즉시 최상단에 보여준다. 조각 하나가 못 읽혀도 나머지는 그대로 표기한다.
async function loadOverview() {
  const box = $("#overview-card");
  if (!box) return;
  // 내용이 없는 빈 막대를 남기지 않는다(B4) — 전에는 먼저 펼치고 조회해, 응답이 오기 전까지 흰 줄만 보였다.
  // 대상에 닿지 않는 인스턴스에서는 그 줄이 계속 남아 화면 결함처럼 읽혔다
  box.hidden = true;
  try {
    const o = await api(`/api/instances/${state.instance.id}/overview`);
    const rep = o.replication || {};
    const badge = LAG_SOURCE_LABEL[rep.lagSource];
    const repLag = rep.lagSource === "MEASURED"
      ? `${fmtNum(rep.lagSeconds, 1)}s`
      : `<span class="verify-badge ${badge ? esc(badge.cls) : "muted"}">${esc(badge ? badge.label : rep.lagSource || "-")}</span>`;
    // RPO 노출("지금 failover하면 잃을 데이터")은 실측 지연 그 자체 — 표현은 여기서(백엔드가 문안을 굽지 않음)
    const rpo = (rep.lagSource === "MEASURED" && rep.lagSeconds != null)
      ? ` <span class="ov-rpo">RPO 노출 ${rep.lagSeconds <= 0 ? "0s (따라잡음)" : "약 " + esc(fmtNum(rep.lagSeconds, 0)) + "s"}</span>`
      : "";
    const bk = o.backup;
    const bkStr = bk
      ? `${esc(BK_STATUS_LABEL[bk.status] ?? bk.status)}` +
        `${bk.elapsedHours != null ? ` · ${fmtNum(bk.elapsedHours, 1)}h 전` : ""}` +
        `${bk.verifyStatus ? ` · 검증 ${esc(bk.verifyStatus)}` : ""}`
      : "이력 없음";
    const meta = [o.environment, o.cluster ? `클러스터 ${o.cluster}` : "", o.teamLabel ? `팀 ${o.teamLabel}` : ""]
      .filter(Boolean).map(esc).join(" · ");
    box.innerHTML = `
      <div class="ov-head">
        <div class="ov-id">
          <span class="ov-type">${esc(o.type)}</span>
          <b class="ov-name">${esc(o.name)}</b>
          ${meta ? `<span class="ov-meta muted">${meta}</span>` : ""}
        </div>
        <div class="ov-health">
          <span class="ov-status ${o.down ? "ov-down" : "ov-up"}">${o.down ? "DOWN" : "UP"}</span>
          <span class="ov-grade grade-${esc(o.grade)}">${esc(o.grade)} · ${esc(String(o.healthScore))}</span>
        </div>
      </div>
      <div class="ov-signals">
        <span class="ov-sig"><span class="ov-sig-k">복제</span> ${esc(rep.role ?? "-")} · 지연 ${repLag}${rpo}</span>
        <span class="ov-sig"><span class="ov-sig-k">백업</span> ${bkStr}</span>
      </div>`;
    box.hidden = false;
  } catch (e) {
    box.innerHTML = `<span class="muted">운영 종합 조회 실패: ${esc(e.message)}</span>`;
    box.hidden = false;
  }
}

async function loadReplication() {
  const box = $("#replication-box");
  try {
    const r = await api(`/api/instances/${state.instance.id}/replication`);
    const badge = LAG_SOURCE_LABEL[r.lagSource];
    const lag = r.lagSource === "MEASURED"
      ? `${fmtNum(r.lagSeconds, 1)}s`
      : `<span class="verify-badge ${badge ? esc(badge.cls) : "muted"}">${esc(badge ? badge.label : r.lagSource)}</span>`;
    box.innerHTML = `role: ${esc(r.role)}<br>지연: ${lag}<br>${esc(r.detail ?? "")}`;
  } catch (e) { box.textContent = `조회 실패: ${e.message}`; }
  loadReplicationSlots();
}

// 복제 슬롯 잔량 (C-1) — 비활성 슬롯이 WAL을 무한 보존해 디스크를 채우는 사각. PG만 결과가 있다.
async function loadReplicationSlots() {
  const box = $("#replication-slots");
  try {
    const slots = await api(`/api/instances/${state.instance.id}/replication-slots`);
    if (!slots.length) { box.textContent = ""; return; }
    box.innerHTML = "복제 슬롯: " + slots.map((s) => {
      const mb = (s.retainedBytes / (1024 * 1024)).toFixed(1);
      const warn = s.walStatus === "lost" || s.walStatus === "unreserved" || (!s.active);
      const label = `${esc(s.slotName)} [${esc(s.walStatus)}${s.active ? "" : ", 비활성"}, 보존 ${mb}MB]`;
      return warn ? `<span class="verify-badge verify-FAILED">${label}</span>` : `<span>${label}</span>`;
    }).join(" ");
  } catch (e) { box.textContent = ""; }
}

// 최근 데드락 (3차 아크 D-축) — DB가 이미 남긴 흔적을 설정 변경 0으로 읽는다.
// MSSQL system_health XE / MySQL INNODB STATUS는 리포트를, PG는 개별 사건이 없어(카운터뿐) 빈 목록이다.
// 롤링 저장이라 "최근"만 본다 — 없으면 "최근 데드락 없음"으로 정직하게 표기(과거 전수 보장 아님).
async function loadDeadlocks() {
  const box = $("#deadlock-result");
  try {
    const rows = await api(`/api/instances/${state.instance.id}/deadlocks?limit=10`);
    if (!rows.length) {
      box.innerHTML = '<p class="muted">최근 데드락 없음 (롤링 저장이라 "최근"만 관측 — PG는 발생 시 알림으로).</p>';
      return;
    }
    box.innerHTML = rows.map((d) => {
      const stmts = (d.statements || []).map((s) => `<code>${esc(s)}</code>`).join("<br>");
      return `<div class="anomaly-item">
        <div><span class="src-badge src-estimated">${esc(d.source)}</span>
          <b>${esc(d.detectedAt || "시각 미상")}</b></div>
        <div class="muted">victim: ${esc(d.victim || "미상")}${d.resource ? " · 리소스: " + esc(d.resource) : ""}</div>
        ${stmts ? `<div class="deadlock-stmts">${stmts}</div>` : ""}
      </div>`;
    }).join("");
  } catch (e) {
    box.innerHTML = `<p class="muted">조회 실패: ${esc(e.message)}</p>`;
  }
}

// 이상 감지 (D1) — 평소(이 요일·시간대 베이스라인) 대비 z-score 이탈 쿼리 목록.
// 이력이 부족하면 판정을 보류하고 "학습 중"으로만 알린다(신규 오탐 방지) — 그 사실을 화면에 정직하게 표기한다.
async function loadAnomalies() {
  const box = $("#anomaly-result");
  try {
    const scan = await api(`/api/instances/${state.instance.id}/anomalies`);
    const parts = [];
    if (scan.anomalies.length === 0) {
      parts.push('<p class="muted">현재 이상 없음 (평소 범위 내).</p>');
    } else {
      parts.push(scan.anomalies.map((q) => {
        const metrics = q.anomalies.map((m) =>
          `<span class="anomaly-metric">${esc(m.metric)}: <b>${fmtNum(m.current)}</b> ` +
          `(평소 ${fmtNum(m.baselineMean)}±${fmtNum(m.baselineStddev)}, z=${fmtNum(m.zScore, 1)})</span>`
        ).join(" ");
        return `<div class="anomaly-item">
          <div class="anomaly-q qtext" data-sql-tip="${esc(q.queryText)}" tabindex="0" aria-describedby="sql-tip">${queryTextHtml(q.queryText)}</div>
          <div class="anomaly-metrics">${metrics}</div>
          <div class="hint">${q.dayOfWeek}요일 ${q.hour}시대 기준 · 관측 ${q.observations}회</div>
        </div>`;
      }).join(""));
    }
    // 학습 중(이력 부족) 쿼리 수를 정직하게 노출 — "아직 판정 못 한다"를 숨기지 않는다.
    if (scan.learningCount > 0) {
      parts.push(`<p class="hint">학습 중(baseline unavailable) 쿼리 ${scan.learningCount}건 — 관측 ${scan.minObservations}회 미만이라 판정 보류.</p>`);
    }
    box.className = "anomaly-result";
    box.innerHTML = parts.join("");
  } catch (e) {
    box.className = "anomaly-result muted";
    box.textContent = `조회 실패: ${e.message}`;
  }
}

// 실행계획 변경(plan flip) — 회귀 감지된 쿼리만 계획을 떠서 비교하므로(부하 원칙),
// 이 목록은 "회귀 + 플랜 변경"의 교집합이다. 비었으면 그 자체가 정보(플랜은 안 갈아탔다).
async function loadPlanChanges() {
  const box = $("#plan-change-result");
  try {
    const changes = await api(`/api/instances/${state.instance.id}/plan-changes`);
    if (!changes.length) {
      box.className = "anomaly-result muted";
      box.textContent = "감지된 플랜 변경 없음 — 회귀 쿼리의 계획이 기준선과 동일하거나, 아직 기준선만 쌓인 상태.";
      return;
    }
    box.className = "anomaly-result";
    box.innerHTML = changes.map((c, idx) => `<div class="anomaly-item">
        <div class="anomaly-q">${esc(c.changedAt.replace("T", " "))} · queryId=${esc(c.queryId)}</div>
        <div class="anomaly-metric"><b>${esc(c.fromShape)}</b> &rarr; <b>${esc(c.toShape)}</b></div>
        <div class="drift-around" id="drift-around-${idx}"></div>
      </div>`).join("");
    // P4 대조 — 각 플랜 플립 무렵(±24h) 설정 변경 수를 붙인다(설정 변경이 플랜을 갈아탄 원인 후보).
    // 운영자·관리자가 아니면 403 — 조용히 생략(플랜 변경 카드 자체는 인증 사용자에게 열려 있다).
    changes.forEach(async (c, idx) => {
      try {
        const r = await api(`/api/instances/${state.instance.id}/config-drift/around?at=${encodeURIComponent(c.changedAt)}&hours=24`);
        if (r.changeCount > 0) {
          $(`#drift-around-${idx}`).innerHTML =
            `<a class="drift-hint" href="/?instance=${state.instance.id}&view=config-drift">± ${r.windowHours}h 내 설정 변경 ${r.changeCount}건 — 원인 후보 확인 ↗</a>`;
        }
      } catch (e) { /* 권한 없음·미수집 — 생략 */ }
    });
  } catch (e) {
    box.className = "anomaly-result muted";
    box.textContent = `조회 실패: ${e.message}`;
  }
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

// 레이턴시 백분위 p95/p99 (D4a → 2차 아크) — 같은 지표라도 기종마다 원자료가 달라 source로 출처를 구분한다.
// 값을 절대 섞지 않는다: 실측 누적(NATIVE)·실측 구간(NATIVE_WINDOWED)·히스토그램 보간(NATIVE_HISTOGRAM)·
// 직접계산(COMPUTED)·추정(ESTIMATED)·미지원(UNSUPPORTED)을 배지로 구분한다. 값이 없으면(null) "-"로.
const LATENCY_SOURCE = {
  NATIVE: { cls: "src-native", label: "실측누적", note: "리셋 이후 누적 — 최근 윈도우 아님" },
  NATIVE_WINDOWED: { cls: "src-native", label: "실측구간", note: "히스토그램 두 스냅샷 차분 — 최근 구간 p95(버킷 상한 근사)" },
  NATIVE_HISTOGRAM: { cls: "src-native", label: "히스토그램", note: "DB 히스토그램 버킷 보간 — 인스턴스/컬렉션 단위(쿼리 단위 아님)" },
  COMPUTED: { cls: "src-computed", label: "직접계산", note: "profile 원샘플에서 계산" },
  ESTIMATED: { cls: "src-estimated", label: "추정", note: "평균+표준편차 근사 — 실제 백분위 아님, 과소평가 가능" },
  UNSUPPORTED: { cls: "src-unsupported", label: "미지원", note: "백분위 원자료 없음" },
};

async function loadLatencyPercentiles() {
  const table = $("#latency-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Source</th><th>Query</th><th class="num">p95(ms)</th><th class="num">p99(ms)</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/latency-percentiles?limit=20`);
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((r) => {
      const src = LATENCY_SOURCE[r.source] ?? { cls: "src-unsupported", label: esc(r.source), note: "" };
      return `
      <tr>
        <td><span class="src-badge ${src.cls}" title="${esc(src.note)}">${src.label}</span></td>
        <td class="qtext" data-sql-tip="${esc(r.queryText)}" tabindex="0" aria-describedby="sql-tip">${queryTextHtml(r.queryText)}</td>
        <td class="num">${r.p95Ms != null ? fmtNum(r.p95Ms) : "-"}</td>
        <td class="num">${r.p99Ms != null ? fmtNum(r.p99Ms) : "-"}</td>
      </tr>`;
    }).join("") : '<tr><td colspan="4" class="muted">백분위 데이터가 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="4" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// ---------- SLO / 에러 버짓 (D4) — 사용자 경험 지표(레이턴시·가용성)로 SLO 대비 버짓 소진을 본다 ----------
// 레이턴시 SLI가 어느 source(p95 실측/직접계산/추정/평균 폴백)인지 배지로 정직하게 표기한다.
const SLO_VERDICT_LABEL = {
  MEETING: "충족", AT_RISK: "임박", BREACHING: "위반", INSUFFICIENT_DATA: "데이터 부족",
  OK: "여유", WARNING: "임박", EXHAUSTED: "소진",
};
// 레이턴시 SLI source 배지 — D4a 4종에 D4의 평균 폴백/데이터 부족을 더한다(라벨을 절대 섞지 않는다)
const SLO_LATENCY_SOURCE = {
  ...LATENCY_SOURCE,
  AVG_FALLBACK: { cls: "src-estimated", label: "평균폴백", note: "백분위 미지원 기종 — 평균 레이턴시로 폴백(꼬리 못 봄)" },
  INSUFFICIENT_DATA: { cls: "src-unsupported", label: "데이터부족", note: "쿼리 통계 없음" },
};

async function loadSloReport() {
  const box = $("#slo-result");
  box.classList.remove("muted");
  let r;
  try {
    r = await api(`/api/instances/${state.instance.id}/slo`);
  } catch (e) {
    box.classList.add("muted");
    box.textContent = `조회 실패: ${e.message}`;
    return;
  }
  const lat = r.latency, av = r.availability, eb = r.errorBudget;
  const src = SLO_LATENCY_SOURCE[lat.source] ?? { cls: "src-unsupported", label: esc(lat.source), note: "" };
  const vlabel = (v) => esc(SLO_VERDICT_LABEL[v] ?? v);

  // 버짓 소진 게이지 — 소진율(%)을 폭으로. 80%↑ 주황, 100%↑ 빨강
  const consumed = eb.budgetConsumedRatio;
  const pctConsumed = consumed == null ? null : Math.min(100, consumed * 100);
  const gaugeCls = consumed == null ? "slo-gauge-ok"
    : consumed >= 1 ? "slo-gauge-over" : consumed >= 0.8 ? "slo-gauge-warn" : "slo-gauge-ok";
  const gauge = consumed == null ? '<div class="slo-sub">데이터 부족 — 게이지 없음</div>'
    : `<div class="slo-gauge"><div class="slo-gauge-fill ${gaugeCls}" style="width:${pctConsumed.toFixed(1)}%"></div></div>
       <div class="slo-sub">소진 ${(consumed * 100).toFixed(1)}% · 잔여 ${(eb.budgetRemainingRatio * 100).toFixed(1)}%</div>`;

  const latValue = lat.observedMs == null ? "—" : `${fmtNum(lat.observedMs)} ms`;
  const latP99 = lat.p99Ms != null ? ` · p99 ${fmtNum(lat.p99Ms)}ms` : "";
  const upValue = av.upRatio == null ? "—" : `${(av.upRatio * 100).toFixed(2)}%`;
  const burn = eb.burnRate == null ? "—" : `${fmtNum(eb.burnRate)}×`;

  box.innerHTML = `
    <div style="margin-bottom:6px">
      <span class="slo-verdict slo-verdict-${esc(r.verdict)}">${vlabel(r.verdict)}</span>
      <span class="slo-sub" style="margin-left:8px">평가 ${esc(String(r.evaluatedAt).replace("T", " ").slice(0, 19))}</span>
    </div>
    <div class="slo-grid">
      <div class="slo-block">
        <h4>레이턴시 SLI <span class="src-badge ${src.cls}" title="${esc(src.note)}">${src.label}</span></h4>
        <div class="slo-metric">${latValue}</div>
        <div class="slo-sub">${lat.source === "AVG_FALLBACK" ? "평균 레이턴시" : "최악 핵심쿼리 p95"}${latP99} · 목표 &lt; ${fmtNum(lat.thresholdMs)}ms
          <span class="slo-badge slo-badge-${esc(lat.verdict)}">${vlabel(lat.verdict)}</span></div>
        ${lat.totalCoreQueries > 0 ? `<div class="slo-sub">임계 초과 핵심쿼리 ${lat.breachingCoreQueries}/${lat.totalCoreQueries}${lat.coreQueryText ? ` · 최악: ${esc(lat.coreQueryText)}` : ""}</div>` : ""}
        <div class="slo-note">${esc(lat.note)}</div>
      </div>
      <div class="slo-block">
        <h4>가용성 SLI</h4>
        <div class="slo-metric">${upValue}</div>
        <div class="slo-sub">목표 ${(av.targetRatio * 100).toFixed(2)}% · 표본 ${av.upSamples}/${av.totalSamples} (${av.windowDays}일)
          <span class="slo-badge slo-badge-${esc(av.verdict)}">${vlabel(av.verdict)}</span></div>
        <div class="slo-note">${esc(av.note)}</div>
      </div>
      <div class="slo-block" style="grid-column:1/-1">
        <h4>에러 버짓 · 번인 레이트
          <span class="slo-badge slo-badge-${esc(eb.verdict)}">${vlabel(eb.verdict)}</span></h4>
        ${gauge}
        <div class="slo-sub" style="margin-top:6px">번인 레이트 ${burn} <span class="hint">(지속가능 속도 대비 배수 · 최근 ${eb.burnWindowMinutes}분)</span>
          · 허용 ${fmtNum(eb.allowedDowntimeMinutes)}분 / 관측 ${fmtNum(eb.observedDowntimeMinutes)}분</div>
        <div class="slo-note">${esc(eb.note)}</div>
      </div>
    </div>`;
}

// 파티션 조회 (D5) — 테이블별 파티션 목록·방식·경계·행수·크기. 조회 전용(생성·삭제 없음).
// MongoDB는 partitionMethod=UNSUPPORTED 안내 행으로 오고, 이때 boundary에 사유가 담긴다 —
// "파티션 없음"과 "이 기종은 원래 파티션 개념이 없음"을 정직하게 구분해 보여준다.
async function loadPartitions() {
  const table = $("#partition-table");
  table.querySelector("thead").innerHTML = `
    <tr><th>Table</th><th>Partition</th><th>Method</th><th>Boundary</th>
        <th class="num">Rows</th><th class="num">Size</th></tr>`;
  try {
    const rows = await api(`/api/instances/${state.instance.id}/partitions?limit=50`);
    if (rows.length && rows[0].partitionMethod === "UNSUPPORTED") {
      table.querySelector("tbody").innerHTML =
        `<tr><td colspan="6" class="muted">미지원 — ${esc(rows[0].boundary)}</td></tr>`;
      return;
    }
    table.querySelector("tbody").innerHTML = rows.length ? rows.map((p) => `
      <tr>
        <td>${esc(p.tableName)}</td>
        <td>${esc(p.partitionName ?? "-")}</td>
        <td>${esc(p.partitionMethod ?? "-")}${p.partitionExpression ? ` <span class="muted">(${esc(p.partitionExpression)})</span>` : ""}</td>
        <td class="qtext" title="${esc(p.boundary ?? "")}">${esc(p.boundary ?? "-")}</td>
        <td class="num">${p.rowCount != null ? fmtNum(p.rowCount, 0) : "-"}</td>
        <td class="num">${p.sizeBytes != null ? fmtBytes(p.sizeBytes) : "-"}</td>
      </tr>`).join("") : '<tr><td colspan="6" class="muted">파티션이 있는 테이블이 없습니다.</td></tr>';
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="6" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// 세션 / 블로킹 (B2) — "지금 누가 누구를 막고 있나". blockedByPid가 있으면 행을 강조한다.
// 세션 종료 능력(운영자·관리자)이 있으면 행마다 취소(force=false)/강제종료(force=true) 버튼을 붙인다. 없으면 버튼 없음.
async function loadSessions() {
  const table = $("#session-table");
  const canKill = can("TARGET_OPERATE");
  const cols = canKill ? 8 : 7;
  table.querySelector("thead").innerHTML = `
    <tr><th class="num">PID</th><th>User</th><th>State</th><th>Wait</th>
        <th class="num">BlockedBy</th><th class="num">Elapsed(ms)</th><th>Query</th>${canKill ? "<th>Action</th>" : ""}</tr>`;
  try {
    renderSessionRows(await api(`/api/instances/${state.instance.id}/sessions?limit=50`));
  } catch (e) {
    table.querySelector("tbody").innerHTML =
      `<tr><td colspan="${cols}" class="muted">조회 실패: ${esc(e.message)}</td></tr>`;
  }
}

// 한 번 조회(loadSessions)와 실시간 프레임이 같은 행 모양을 쓴다 — 두 경로의 표가 달라 보이면 어느 쪽이 맞는지 묻게 된다
function renderSessionRows(rows) {
  const table = $("#session-table");
  const canKill = can("TARGET_OPERATE");
  const cols = canKill ? 8 : 7;
  table.querySelector("tbody").innerHTML = rows.length ? rows.map((s) => `
      <tr class="${s.blockedByPid != null ? "blocked-row" : ""}">
        <td class="num">${esc(s.pid)}</td>
        <td>${esc(s.user ?? "-")}</td>
        <td>${esc(s.state ?? "-")}</td>
        <td title="${esc(s.waitEvent ?? "")}">${esc(s.waitEvent ?? "-")}</td>
        <td class="num">${s.blockedByPid != null ? `<span class="blocked-by">${esc(s.blockedByPid)}</span>` : "-"}</td>
        <td class="num">${fmtNum(s.elapsedMs)}</td>
        <td class="qtext" data-sql-tip="${esc(s.query)}" tabindex="0" aria-describedby="sql-tip">${queryTextHtml(s.query)}</td>
        ${canKill ? `<td class="session-actions">
          <button class="btn btn-small" data-kill="${esc(s.pid)}" data-force="false">취소</button>
          <button class="btn btn-small btn-danger" data-kill="${esc(s.pid)}" data-force="true">강제종료</button>
        </td>` : ""}
      </tr>`).join("") : `<tr><td colspan="${cols}" class="muted">활성 세션이 없습니다.</td></tr>`;
  if (canKill) wireKillButtons();
}

// ---------- 실시간 세션 (VERIFICATION 140절) ----------
// 서버가 대상별로 한 번 조회해 구독자 전원에게 나눠 주는 SSE를 받는다(LiveSessionHub).
// 탭이 숨었거나 카드가 안 보이면 연결을 닫는다 — 안 보는 화면이 구독자로 남으면 대상 조회가 멈추지 않는다.
const live = { on: false, source: null, instanceId: null, history: [], lastSeq: 0, hover: false, pending: null };
const LIVE_HISTORY = 60;

function setupLive() {
  const btn = $("#live-toggle");
  if (!btn) return;
  btn.addEventListener("click", () => {
    live.on = !live.on;
    live.history = [];
    drawLiveSpark();
    syncLive();
  });
  document.addEventListener("visibilitychange", syncLive);
  // 표를 읽거나 kill 버튼을 누르려는 동안 행이 바뀌면 엉뚱한 pid를 누른다 — 포인터가 표 위에 있으면 그리기만 미룬다
  const table = $("#session-table");
  table.addEventListener("pointerenter", () => { live.hover = true; });
  table.addEventListener("pointerleave", () => {
    live.hover = false;
    if (live.pending) { renderSessionRows(live.pending); live.pending = null; }
  });
}

function syncLive() {
  const btn = $("#live-toggle");
  if (!btn) return;
  btn.setAttribute("aria-pressed", String(live.on));
  btn.classList.toggle("on", live.on);
  btn.textContent = live.on ? "실시간 끄기" : "실시간 켜기";
  const card = $(".session-card");
  const visible = document.visibilityState === "visible" && card.offsetParent !== null;
  const want = live.on && state.instance && visible;
  if (want && live.source && live.instanceId === state.instance.id) return;
  closeLive();
  if (!want) {
    setLiveStatus(live.on ? "일시정지 — 화면이 보이지 않아 연결을 닫았습니다" : "꺼짐 — 켜면 대상 조회 한 번을 보는 사람 모두가 나눠 받습니다");
    return;
  }
  live.instanceId = state.instance.id;
  const es = new EventSource(`/api/instances/${live.instanceId}/live/sessions`);
  live.source = es;
  setLiveStatus("연결 중...");
  // 재연결하면 서버 채널이 새로 시작됐을 수 있어 seq 기준을 버린다 — 안 버리면 없는 결번을 보고한다
  es.onopen = () => { live.lastSeq = 0; };
  es.addEventListener("frame", (ev) => onLiveFrame(JSON.parse(ev.data)));
  es.onerror = () => {
    if (es.readyState === EventSource.CLOSED) {
      // 로그인 만료(302)·상한 초과(503)는 EventSource가 다시 붙지 않는다 — 켜진 척하지 않고 꺼 둔다
      closeLive();
      live.on = false;
      syncLive();
      setLiveStatus("연결이 끊겼습니다 — 다시 로그인했거나 잠시 뒤 다시 켜세요", "err");
    } else {
      setLiveStatus("재연결 중...");
    }
  };
}

function closeLive() {
  live.source?.close();
  live.source = null;
  live.pending = null;
}

function onLiveFrame(f) {
  if (f.instanceId !== state.instance?.id) return;
  const time = new Date(f.atEpochMs).toLocaleTimeString("ko-KR", { hour12: false });
  const gap = live.lastSeq && f.seq > live.lastSeq + 1 ? ` · 놓친 갱신 ${f.seq - live.lastSeq - 1}회` : "";
  live.lastSeq = f.seq;
  if (f.status === "GONE") {
    live.on = false;
    closeLive();
    syncLive();
    setLiveStatus(f.error ?? "인스턴스가 사라졌습니다", "err");
    return;
  }
  if (f.status === "ERROR") {
    // 마지막으로 성공한 표는 남긴다 — 순간 실패로 표가 비면 "세션이 다 사라졌다"로 읽힌다
    setLiveStatus(`LIVE · ${time} 조회 실패: ${f.error ?? ""}${gap}`, "err");
    return;
  }
  const s = f.summary;
  live.history.push({ total: s.total, blocked: s.blocked });
  if (live.history.length > LIVE_HISTORY) live.history.shift();
  drawLiveSpark();
  const paused = live.hover ? " · 표 위에 포인터가 있어 표 갱신을 멈춤" : "";
  setLiveStatus(`LIVE · ${time} · 세션 ${s.total} · 막힘 ${s.blocked} · 대기 ${s.waiting} · 최장 ${fmtNum(s.longestMs)}ms · 수집 ${fmtNum(f.collectMs)}ms${gap}${paused}`,
    s.blocked > 0 ? "warn" : "ok");
  if (live.hover) live.pending = f.sessions;
  else renderSessionRows(f.sessions);
}

// 화면 밖으로 튀어나오는 브라우저 경고창을 쓰지 않는다(162절) — 인스턴스 목록 위 한 줄로 알린다.
// 자리가 없으면 조용히 삼키지 않고 콘솔에 남긴다 — "알렸다고 착각"이 가장 나쁘다.
function setInstanceNotice(text) {
  const host = document.getElementById("instance-notice");
  if (!host) { console.warn("[dbtower] " + text); return; }
  host.hidden = false;
  host.innerHTML = `<div class="finding-item">${esc(text)}</div>`;
  clearTimeout(setInstanceNotice.timer);
  setInstanceNotice.timer = setTimeout(() => { host.hidden = true; host.innerHTML = ""; }, 8000);
}

function setLiveStatus(text, tone) {
  const el = $("#live-status");
  el.textContent = text;
  el.dataset.tone = tone ?? "";
}

// 최근 2분 추이 — 세션 수(선)와 막힌 세션(붉은 선). 값은 서버가 센 숫자뿐이라 문자열이 끼어들 자리가 없다
function drawLiveSpark() {
  const svg = $("#live-spark");
  const h = live.history;
  if (h.length < 2) { svg.innerHTML = ""; svg.toggleAttribute("hidden", true); return; }
  svg.toggleAttribute("hidden", false);
  const max = Math.max(1, ...h.map((p) => p.total));
  const line = (key) => h.map((p, i) => `${((i / (LIVE_HISTORY - 1)) * 240).toFixed(1)},${(34 - (p[key] / max) * 30).toFixed(1)}`).join(" ");
  svg.innerHTML = `<title>최근 ${h.length}회 갱신 — 세션 수(파랑)·막힌 세션(빨강), 최대 ${max}</title>
    <polyline class="spark-total" points="${line("total")}"/><polyline class="spark-blocked" points="${line("blocked")}"/>`;
}

// kill은 confirm 없이 바로 POST한다(장애 시 빠른 처치가 목적) — 대신 버튼 자체가 운영자·관리자에게만 보인다.
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
        setLiveStatus(`세션 종료 실패: ${e.message}`, "bad");
      }
    });
  });
}

// ---------- Schema Diff (B7) — 같은 역할의 두 인스턴스 구조 비교 ----------
// 드롭다운 두 개는 등록된 인스턴스 전체 목록에서 채운다(현재 선택된 인스턴스와 무관 — 두 대를 자유 비교).
function populateSchemaSelects(list) {
  const opts = list.map((i) => `<option value="${i.id}">${esc(i.name)} · ${esc(i.type)}</option>`).join("");
  // Schema Diff(B7)와 파라미터 드리프트(B6) 두 카드의 좌/우 드롭다운을 같은 목록으로 채운다
  [["#schema-left", "#schema-right"], ["#param-left", "#param-right"]].forEach(([lSel, rSel]) => {
    const left = $(lSel), right = $(rSel);
    if (!left || !right) return;
    left.innerHTML = opts;
    right.innerHTML = opts;
    if (list.length > 1) right.selectedIndex = 1; // 기본값: 서로 다른 두 대
    left._csSync?.(); right._csSync?.();          // 커스텀 드롭다운이면 버튼 동기화
    enhanceSelect(left); enhanceSelect(right);    // 사이드바 필터와 같은 커스텀 드롭다운으로 통일
  });
}

// 인덱스 한 줄 표기 — (col1, col2) UNIQUE. 값은 전부 esc()로 이스케이프한다(XSS 방지).
function idxText(x) {
  return `(${(x.columns || []).map(esc).join(", ")})${x.unique ? " UNIQUE" : ""}`;
}
const notNull = (nullable) => (nullable ? "" : " NOT NULL");

async function runSchemaDiff() {
  const left = $("#schema-left").value, right = $("#schema-right").value;
  const box = $("#schema-diff-result"), warnBox = $("#schema-diff-warning");
  if (!left || !right) return;
  box.classList.remove("muted");
  box.innerHTML = '<div class="muted">비교 중...</div>';
  warnBox.hidden = true;
  let d;
  try {
    d = await api(`/api/schema-diff?left=${left}&right=${right}`);
  } catch (e) {
    box.innerHTML = `<div class="schema-warning">비교 실패: ${esc(e.message)}</div>`;
    return;
  }
  if (d.warning) { warnBox.hidden = false; warnBox.textContent = `주의: ${d.warning}`; }
  if (d.identical) {
    box.innerHTML = `<div class="schema-same">${d.complete ? "비교한 구조에서 차이가 없습니다." : "확보한 구조에서 차이가 없습니다. 미확보·UNSUPPORTED 항목은 비교하지 않았습니다."}</div>`;
    return;
  }
  const parts = [];
  const line = (cls, mark, text) => `<div class="schema-line ${cls}">${mark} ${text}</div>`;
  const tableMeta = (t) => `<span class="muted">(${t.columns.length} cols · ${t.indexes.length} idx)</span>`;
  const fkDiffText = (f) => `${esc((f.columns ?? []).join(", "))} → ${esc(f.refTable)}(${esc((f.refColumns ?? []).join(", "))})`
    + (f.onDelete && f.onDelete !== "NO ACTION" ? ` ON DELETE ${esc(f.onDelete)}` : "");

  if (d.addedTables.length) {
    parts.push('<div class="schema-block"><h4>추가된 테이블 <span class="hint">(right에만)</span></h4>' +
      d.addedTables.map((t) => line("schema-add", "+", `${esc(t.name)} ${tableMeta(t)}`)).join("") + "</div>");
  }
  if (d.removedTables.length) {
    parts.push('<div class="schema-block"><h4>삭제된 테이블 <span class="hint">(left에만)</span></h4>' +
      d.removedTables.map((t) => line("schema-del", "−", `${esc(t.name)} ${tableMeta(t)}`)).join("") + "</div>");
  }
  d.changedTables.forEach((t) => {
    const lines = [];
    t.addedColumns.forEach((c) => lines.push(line("schema-add", "+", `컬럼 ${esc(c.name)} ${esc(c.type)}${notNull(c.nullable)}`)));
    t.removedColumns.forEach((c) => lines.push(line("schema-del", "−", `컬럼 ${esc(c.name)} ${esc(c.type)}`)));
    t.changedColumns.forEach((c) => lines.push(line("schema-chg", "~",
      `컬럼 ${esc(c.name)}: ${esc(c.leftType)}${notNull(c.leftNullable)} → ${esc(c.rightType)}${notNull(c.rightNullable)}`)));
    t.addedIndexes.forEach((x) => lines.push(line("schema-add", "+", `인덱스 ${esc(x.name)} ${idxText(x)}`)));
    t.removedIndexes.forEach((x) => lines.push(line("schema-del", "−", `인덱스 ${esc(x.name)} ${idxText(x)}`)));
    t.changedIndexes.forEach((x) => lines.push(line("schema-chg", "~",
      `인덱스 ${esc(x.name)}: ${idxText(x.left)} → ${idxText(x.right)}`)));
    // 외래키(153절) — 구조 스냅샷에 제약조건이 들어와 "왜 저 장비만 다르지"에 참조 무결성도 보인다
    (t.addedForeignKeys ?? []).forEach((f) => lines.push(line("schema-add", "+", `외래키 ${esc(f.name)} ${fkDiffText(f)}`)));
    (t.removedForeignKeys ?? []).forEach((f) => lines.push(line("schema-del", "−", `외래키 ${esc(f.name)} ${fkDiffText(f)}`)));
    (t.changedForeignKeys ?? []).forEach((f) => lines.push(line("schema-chg", "~",
      `외래키 ${esc(f.name)}: ${fkDiffText(f.left)} → ${fkDiffText(f.right)}`)));
    [["CHECK", t.checks], ["트리거", t.triggers]].forEach(([label, changes]) => {
      const definition = (d) => `${esc(d.definition ?? "미확보")} [${esc(d.state ?? "미확보")}]`;
      (changes?.added ?? []).forEach((d) => lines.push(line("schema-add", "+", `${label} ${esc(d.name)}: ${definition(d)}`)));
      (changes?.removed ?? []).forEach((d) => lines.push(line("schema-del", "−", `${label} ${esc(d.name)}: ${definition(d)}`)));
      (changes?.changed ?? []).forEach((d) => lines.push(line("schema-chg", "~", `${label} ${esc(d.name)}: ${definition(d.left)} → ${definition(d.right)}`)));
    });
    parts.push(`<div class="schema-block"><h4>변경된 테이블: ${esc(t.table)}</h4>${lines.join("")}</div>`);
  });
  box.innerHTML = parts.join("");
}

// ---------- 파라미터 드리프트 (B6) — 같은 역할 두 인스턴스 설정값 비교 ----------
async function runParamDiff() {
  const left = $("#param-left").value, right = $("#param-right").value;
  const box = $("#param-diff-result"), warnBox = $("#param-diff-warning");
  if (!left || !right) return;
  box.classList.remove("muted");
  box.innerHTML = '<div class="muted">비교 중...</div>';
  warnBox.hidden = true;
  let d;
  try {
    d = await api(`/api/param-diff?left=${left}&right=${right}`);
  } catch (e) {
    box.innerHTML = e.message.startsWith("403")
      ? '<div class="schema-warning">파라미터 드리프트는 운영자·관리자만 볼 수 있습니다.</div>'
      : `<div class="schema-warning">비교 실패: ${esc(e.message)}</div>`;
    return;
  }
  if (d.warning) { warnBox.hidden = false; warnBox.textContent = `주의: ${d.warning}`; }
  if (d.identical) {
    box.innerHTML = '<div class="schema-same">두 인스턴스 파라미터가 동일합니다 — 드리프트 없음.</div>';
    return;
  }
  const parts = [];
  const line = (cls, mark, text) => `<div class="schema-line ${cls}">${mark} ${text}</div>`;
  if (d.changed.length) {
    // 값이 다른 항목은 표로 — name / left / right 한눈에 비교
    const rows = d.changed.map((c) => `
      <tr>
        <td class="qtext" title="${esc(c.name)}">${esc(c.name)}</td>
        <td>${esc(c.leftValue)}</td>
        <td>${esc(c.rightValue)}</td>
      </tr>`).join("");
    parts.push(`<div class="schema-block"><h4>값이 다른 파라미터 <span class="hint">(${d.changed.length})</span></h4>
      <div class="table-scroll"><table class="qtable param-diff-table">
        <thead><tr><th>name</th><th>left</th><th>right</th></tr></thead>
        <tbody>${rows}</tbody></table></div></div>`);
  }
  if (d.leftOnly.length) {
    parts.push('<div class="schema-block"><h4>left에만 있는 파라미터</h4>' +
      d.leftOnly.map((p) => line("schema-del", "−", `${esc(p.name)} = ${esc(p.value)}`)).join("") + "</div>");
  }
  if (d.rightOnly.length) {
    parts.push('<div class="schema-block"><h4>right에만 있는 파라미터</h4>' +
      d.rightOnly.map((p) => line("schema-add", "+", `${esc(p.name)} = ${esc(p.value)}`)).join("") + "</div>");
  }
  box.innerHTML = parts.join("");
}

// ---------- 설정 변경 이력 (B1) — 시간축 드리프트: 언제부터 무엇이 바뀌었나 ----------
async function loadConfigDrift() {
  const box = $("#config-drift-result");
  if (!state.instance) { box.className = "config-drift-result muted"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  box.className = "config-drift-result muted";
  box.innerHTML = '<div class="muted">조회 중...</div>';
  let rows;
  try {
    rows = await api(`/api/instances/${state.instance.id}/config-drift?limit=100`);
  } catch (e) {
    box.innerHTML = e.message.startsWith("403")
      ? '<div class="schema-warning">설정 변경 이력은 운영자·관리자만 볼 수 있습니다.</div>'
      : `<div class="schema-warning">조회 실패: ${esc(e.message)}</div>`;
    return;
  }
  box.className = "config-drift-result";
  if (!rows.length) {
    box.innerHTML = '<div class="schema-same">기록된 설정 변경이 없습니다 — 첫 수집(기준선) 이후 변동 없음이거나 아직 수집 전입니다.</div>';
    return;
  }
  // 변경을 시각별로 묶는다(같은 captured_at = 한 번의 수집에서 함께 바뀐 것)
  const byTime = new Map();
  for (const r of rows) {
    const t = (r.capturedAt || "").replace("T", " ").slice(0, 19);
    if (!byTime.has(t)) byTime.set(t, []);
    byTime.get(t).push(r);
  }
  const kindCell = (r) => {
    if (r.kind === "ADDED") return `<span class="drift-add">추가</span> <code>${esc(r.paramName)}</code> = ${esc(r.newValue)}`;
    if (r.kind === "REMOVED") return `<span class="drift-del">제거</span> <code>${esc(r.paramName)}</code> <span class="muted">(이전 ${esc(r.oldValue)})</span>`;
    return `<span class="drift-chg">변경</span> <code>${esc(r.paramName)}</code>: ${esc(r.oldValue)} <span class="schema-arrow">→</span> ${esc(r.newValue)}`;
  };
  const blocks = [];
  for (const [t, changes] of byTime) {
    blocks.push(`<div class="drift-block">
      <div class="drift-time">${esc(t)} <span class="hint">(${changes.length}건)</span></div>
      ${changes.map((r) => `<div class="drift-line">${kindCell(r)}</div>`).join("")}
    </div>`);
  }
  box.innerHTML = `<p class="hint">"누가" 바꿨는지는 대상 DB가 알려주지 않아 표기하지 않습니다 — 대상 DB의 감사 로그에서 확인하세요.</p>` + blocks.join("");
}

// ---------- 스키마 변경 리뷰 게이트 (B2) — 판정·승인·기록(실행은 안 함) ----------
async function submitReview() {
  if (!state.instance) return;
  const sql = $("#review-sql").value.trim();
  if (!sql) { $("#review-list").className = "review-list schema-warning"; $("#review-list").textContent = "리뷰할 SQL을 입력하세요."; return; }
  const reason = $("#review-reason").value.trim();
  const btn = $("#btn-review-submit");
  btn.disabled = true;
  try {
    await api(`/api/instances/${state.instance.id}/reviews`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sql, reason }),
    });
    $("#review-sql").value = ""; $("#review-reason").value = "";
    await loadReviews();
  } catch (e) {
    $("#review-list").className = "review-list schema-warning";
    $("#review-list").textContent = `요청 실패: ${e.message}`;
  } finally { btn.disabled = false; }
}

async function loadReviews() {
  const box = $("#review-list");
  if (!state.instance) return;
  box.className = "review-list muted"; box.innerHTML = '<div class="muted">조회 중...</div>';
  let rows;
  try {
    rows = await api(`/api/instances/${state.instance.id}/reviews`);
  } catch (e) { box.innerHTML = `<div class="schema-warning">조회 실패: ${esc(e.message)}</div>`; return; }
  box.className = "review-list";
  if (!rows.length) { box.innerHTML = '<div class="muted">아직 리뷰 요청이 없습니다.</div>'; return; }
  const canDecide = can("CHANGE_APPROVE");
  box.innerHTML = rows.map((r) => {
    const badge = r.status === "PENDING" ? '<span class="rv-pending">대기</span>'
      : r.status === "APPROVED" ? '<span class="rv-approved">승인</span>'
      : r.status === "EXECUTED" ? '<span class="rv-approved">실행됨</span>'
      : r.status === "ROLLED_BACK" ? '<span class="rv-pending">되돌림</span>'
      : r.status === "EXECUTING" || r.status === "ROLLING_BACK" ? '<span class="rv-pending">실행 중</span>'
      : r.status === "CANCELLED" ? '<span class="rv-rejected">취소</span>'
      : '<span class="rv-rejected">반려</span>';
    const workbenchLink = can("WORKBENCH")
      ? `<a class="muted" href="/?mode=workbench&amp;instance=${esc(encodeURIComponent(r.instanceId))}&amp;ticket=${esc(encodeURIComponent(r.id))}">워크벤치에서 티켓 열기</a>` : "";
    const findings = (r.findings || []).map((f) => `<li>${esc(f)}</li>`).join("");
    const ai = r.aiOpinion ? `<div class="rv-ai"><b>AI 1차 소견:</b> ${esc(r.aiOpinion)}</div>` : "";
    const limited = r.parseLimited ? '<div class="rv-limited">다중 문장·복잡 구문 — 규칙 판정이 불완전할 수 있습니다(사람이 전체 확인).</div>' : "";
    const decided = r.status !== "PENDING"
      // 서버 시각은 오프셋 없는 UTC라 원문을 자르면 워크벤치(브라우저 시간대)와 9시간 어긋나 보였다(136절) — 슬로우 시각과 같은 변환을 쓴다
      ? `<div class="rv-decided muted">${esc(r.decidedBy || "")} · ${fmtSlowTime(r.decidedAt)}${r.decisionComment ? " · " + esc(r.decisionComment) : ""}</div>` : "";
    const actions = (r.status === "PENDING" && canDecide)
      ? `<div class="rv-actions">
           <button class="btn btn-small btn-primary" onclick="decideReview(${r.id}, true)">승인</button>
           <button class="btn btn-small btn-danger" onclick="decideReview(${r.id}, false)">반려</button>
         </div>`
      : (r.status === "PENDING" ? '<div class="hint">승인/반려는 승인자(APPROVER)·관리자만 합니다.</div>' : "");
    return `<div class="rv-item">
      <div class="rv-head">#${r.id} ${badge} <span class="muted">${esc(r.requester)} · rules v${r.rulesVersion}</span> ${workbenchLink}</div>
      <pre class="rv-sql codeblock">${esc(r.targetSql)}</pre>
      ${r.reason ? `<div class="rv-reason muted">사유: ${esc(r.reason)}</div>` : ""}
      <ul class="rv-findings">${findings}</ul>
      ${ai}${limited}${actions}${decided}
    </div>`;
  }).join("");
}

async function decideReview(id, approved) {
  const comment = approved ? "" : (prompt("반려 사유(선택):") ?? "");
  try {
    await api(`/api/reviews/${id}/decision`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ approved, comment }),
    });
    await loadReviews();
  } catch (e) {
    const msg = e.message.startsWith("403")
      ? "승인/반려는 승인자(APPROVER)·관리자만 합니다."
      : `처리 실패: ${e.message}`;
    const box = document.getElementById("review-list");
    if (box) box.insertAdjacentHTML("afterbegin", `<div class="finding-item">${esc(msg)}</div>`);
  }
}

// ---------- 인시던트 리포트 (B4) — 장애 구간을 신호로 재구성 ----------
let lastIncidentMarkdown = "";

function isoLocal(d) {
  // datetime-local 값 형식(YYYY-MM-DDTHH:MM) — 로컬 시각 기준
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

function incidentDefaults() {
  const to = new Date(), from = new Date(to.getTime() - 2 * 3600 * 1000);
  if (!$("#incident-from").value) $("#incident-from").value = isoLocal(from);
  if (!$("#incident-to").value) $("#incident-to").value = isoLocal(to);
}

// 아주 작은 마크다운 렌더러(의존성 0) — 리포트가 쓰는 부분집합(h1/h2·표·불릿·인라인 코드)만.
function mdToHtml(md) {
  const inline = (s) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');
  const lines = md.split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const l = lines[i];
    if (l.startsWith("# ")) { out.push(`<h2>${inline(l.slice(2))}</h2>`); i++; continue; }
    if (l.startsWith("## ")) { out.push(`<h3>${inline(l.slice(3))}</h3>`); i++; continue; }
    if (l.startsWith("|")) {
      // 표 행은 전부 "|"로 시작(구분선 |---|---| 포함) — 구분선까지 모아야 헤더/본문이 안 쪼개진다
      const rows = [];
      while (i < lines.length && lines[i].startsWith("|")) { rows.push(lines[i]); i++; }
      const cells = (r) => r.split("|").slice(1, -1).map((c) => c.trim());
      const isSep = (r) => /^\|[\s\-:|]+\|$/.test(r);
      const head = cells(rows[0]);
      const body = rows.slice(1).filter((r) => !isSep(r))
        .map((r) => `<tr>${cells(r).map((c) => `<td>${inline(c)}</td>`).join("")}</tr>`).join("");
      // 표를 스크롤 상자에 넣는다 — 리포트 표는 열 수·머리 글자가 AI가 만든 값이라 좁은 화면에서
      // 그대로 두면 문서 폭을 밀어 페이지가 옆으로 넘친다(B6)
      out.push(`<div class="table-scroll"><table class="qtable incident-table"><thead><tr>${head.map((h) => `<th>${inline(h)}</th>`).join("")}</tr></thead><tbody>${body}</tbody></table></div>`);
      continue;
    }
    if (l.startsWith("- ")) {
      const items = [];
      while (i < lines.length && lines[i].startsWith("- ")) { items.push(`<li>${inline(lines[i].slice(2))}</li>`); i++; }
      out.push(`<ul class="incident-list">${items.join("")}</ul>`);
      continue;
    }
    if (l.trim()) out.push(`<p>${inline(l)}</p>`);
    i++;
  }
  return out.join("");
}

async function generateIncident() {
  const box = $("#incident-result");
  if (!state.instance) { box.className = "incident-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const fromRaw = $("#incident-from").value, toRaw = $("#incident-to").value;
  if (!fromRaw || !toRaw) { box.className = "incident-result schema-warning"; box.textContent = "구간 시작·끝을 고르세요."; return; }
  // 로컬 벽시계 → UTC ISO(다른 조회와 동일한 toApiTime) — 서버는 UTC 저장이라 변환 없이 보내면 구간이 어긋난다
  const from = toApiTime(fromRaw), to = toApiTime(toRaw);
  const btn = $("#btn-incident"); btn.disabled = true;
  box.className = "incident-result muted"; box.innerHTML = '<div class="muted">재료(시점 비교·설정 변경·플랜 플립·대기·가용성) 조립 중...</div>';
  // 흘려 받는다(146절) — 재료는 AI 전에 이미 다 모인다. AI 요약만 빠진 리포트를 먼저 그리고, 요약 칸을 쓰이는 대로 채운다.
  // 다운로드·카드는 서버가 완성본으로 만든 마크다운이다
  const startedAt = Date.now();
  let summary = "", r = null;
  try {
    await streamSse(`/api/instances/${state.instance.id}/incident-report/stream?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&publish=true`, {}, (name, data) => {
      if (name === "draft") {
        box.className = "incident-result";
        box.innerHTML = `<div class="incident-ai-live"><h2>AI 요약</h2><div class="muted" id="incident-ai-stage">재료 ${((Date.now() - startedAt) / 1000).toFixed(1)}초 · AI가 재료만으로 요약하는 중</div>
          <p id="incident-ai-text"></p></div>${mdToHtml(data.markdown)}`;
      } else if (name === "text") {
        summary += data.delta;
        const el = $("#incident-ai-text");
        if (el) el.textContent = stripEmoji(summary);
      } else if (name === "result") {
        r = data;
      } else if (name === "error") {
        throw new Error(data.message);
      }
    });
    if (!r) throw new Error("리포트가 끝까지 오지 않았습니다(연결 끊김)");
    lastIncidentMarkdown = r.markdown;
    box.className = "incident-result";
    box.innerHTML = mdToHtml(r.markdown);
    $("#btn-incident-dl").hidden = false;
  } catch (e) {
    box.className = "incident-result schema-warning";
    box.textContent = e.message.startsWith("403") ? "인시던트 리포트는 운영자·관리자만 생성할 수 있습니다." : `생성 실패: ${e.message}`;
  } finally { btn.disabled = false; }
}

function downloadIncident() {
  if (!lastIncidentMarkdown) return;
  const blob = new Blob([lastIncidentMarkdown], { type: "text/markdown" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `incident-${state.instance?.name || "report"}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
}

// ---------- 월간 점검 리포트 (B5) — 기간 전체의 건강을 한 장으로 ----------
let lastMonthlyMarkdown = "";

async function generateMonthly() {
  const box = $("#monthly-result");
  if (!state.instance) { box.className = "incident-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const days = Number($("#monthly-days").value) || 30;
  const btn = $("#btn-monthly"); btn.disabled = true;
  box.className = "incident-result muted"; box.innerHTML = '<div class="muted">점검 리포트 조립 중...</div>';
  try {
    const r = await api(`/api/instances/${state.instance.id}/monthly-report?days=${days}`, { method: "POST" });
    lastMonthlyMarkdown = r.markdown;
    box.className = "incident-result";
    box.innerHTML = mdToHtml(r.markdown);
    $("#btn-monthly-dl").hidden = false;
  } catch (e) {
    box.className = "incident-result schema-warning";
    box.textContent = e.message.startsWith("403") ? "월간 리포트는 운영자·관리자만 생성할 수 있습니다." : `생성 실패: ${e.message}`;
  } finally { btn.disabled = false; }
}

function downloadMonthly() {
  if (!lastMonthlyMarkdown) return;
  const blob = new Blob([lastMonthlyMarkdown], { type: "text/markdown" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = `monthly-${state.instance?.name || "report"}.md`;
  a.click();
  URL.revokeObjectURL(a.href);
}

// ---------- 온라인 스키마 변경 (B4) — gh-ost, MySQL 전용 ----------
// 기본은 dry-run(noop). "실제 실행"은 confirm으로 한 번 더 막는다(파괴적 행위).
// 결과 3-값(OK/FAILED/UNSUPPORTED)을 색으로 구분해 정직하게 보여준다.
async function runOnlineDdl(execute) {
  const box = $("#ddl-result");
  if (!state.instance) { box.className = "ddl-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const table = $("#ddl-table").value.trim(), alter = $("#ddl-alter").value.trim();
  if (!table || !alter) { box.className = "ddl-result schema-warning"; box.textContent = "테이블과 ALTER 절을 모두 입력하세요."; return; }
  if (execute && !confirm(`실제로 ${esc(table)} 테이블에 ALTER를 적용합니다.\n\n${alter}\n\n계속할까요?`)) return;

  box.className = "ddl-result muted";
  box.textContent = execute ? "gh-ost 실행 중..." : "gh-ost dry-run(noop) 중...";
  let d;
  try {
    d = await api(`/api/instances/${state.instance.id}/online-ddl`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ table, alter, execute }),
    });
  } catch (e) {
    box.className = "ddl-result schema-warning";
    box.textContent = `요청 실패: ${e.message}`;
    return;
  }
  const cls = { OK: "schema-same", FAILED: "schema-warning", UNSUPPORTED: "muted" }[d.status] || "muted";
  box.className = `ddl-result ${cls}`;
  const ghost = d.ghostTable ? ` · 고스트 테이블: ${esc(d.ghostTable)}` : "";
  box.innerHTML = `<strong>${esc(d.status)}</strong>${d.mode ? ` (${esc(d.mode)})` : ""}${ghost}<br>${esc(d.detail || "")}`;
}

// 서버가 흘리는 SSE를 POST로 받는다(141절) — EventSource는 본문과 CSRF 헤더를 실을 수 없다. workbench/api.js streamEvents와 같은 규칙.
// Accept에 text/event-stream을 싣지 않는다: 스트림을 열기 전 거절(404·503)은 JSON으로 오는데 그걸 못 받는다고 선언하면 406이 된다
async function streamSse(path, body, onEvent, signal) {
  const r = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify(body),
    signal,
  });
  if (r.status === 401) { location.href = "/login.html"; throw new Error("로그인이 필요합니다"); }
  if (!r.ok || !(r.headers.get("Content-Type") || "").startsWith("text/event-stream")) {
    const t = await r.text();
    throw new Error(`${r.status} ${t}`);
  }
  const reader = r.body.getReader();
  const decoder = new TextDecoder();
  let buf = "";
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      // SSE는 줄 끝으로 CRLF·CR도 허용한다. 청크 끝의 CR은 다음 청크의 LF와 짝일 수 있어 남겨 둔다
      buf = (buf + decoder.decode(value, { stream: true })).replace(/\r\n|\r(?!$)/g, "\n");
      let cut;
      while ((cut = buf.indexOf("\n\n")) >= 0) {
        const block = buf.slice(0, cut);
        buf = buf.slice(cut + 2);
        let name = "message";
        const data = [];
        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          else if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
        }
        if (data.length) onEvent(name, JSON.parse(data.join("\n")));
      }
    }
  } catch (e) {
    // 오류 이벤트로 던지면 응답 본문을 닫고 올린다 — 닫지 않으면 GC될 때까지 연결이 열려 있다(148절 감사)
    reader.cancel().catch(() => {});
    throw e;
  }
}

// ---------- AI 어시스턴트 채팅 (자연어 진단) ----------
// 대화는 서버에 남는다(V46·176절): 인스턴스를 고르면 가장 최근 대화를 열어 턴을 그대로 그리고, 첫 질문을 보낼 때
// 새 대화를 만든다(빈 대화가 목록에 쌓이지 않게). 앞선 맥락은 서버가 그 대화의 DB 턴에서만 만든다 — 화면은
// conversationId만 보낸다. 예전에는 브라우저가 만든 history를 보냈고, 그건 위조할 수 있는 입력이었다.
// 한 번에 한 진단만 돈다(진단은 100초를 넘기도 하고, 두 스트림이 같은 칸에 번갈아 그렸다 — 148절 감사).
const chat = { byInstance: new Map(), running: null, confirmId: null };
const CHAT_CONFIDENCE = { high: "높음", medium: "보통", low: "낮음" };
// 예시는 진단 도구로 실제로 답할 수 있는 것만 둔다(query_stats·compare, sessions, replication)
const CHAT_SUGGESTIONS = ["최근 1시간 동안 느려진 쿼리가 있어?", "지금 락을 기다리는 세션이 있어?", "복제가 밀리고 있어?"];

// 인스턴스별 대화 상태. 화면 메모리에만 있고 서버가 진실이다 — sessionStorage에 두던 시절에는 새로고침 뒤
// 화면과 서버가 어긋났고, 브라우저마다 다른 대화가 목록처럼 보였다.
function chatState() {
  const id = state.instance?.id;
  if (id == null) return null;
  if (!chat.byInstance.has(id)) {
    chat.byInstance.set(id, { conversationId: null, title: "", turns: [], list: [], status: "idle", error: null });
  }
  return chat.byInstance.get(id);
}

function chatTurns() {
  return chatState()?.turns ?? [];
}

/** 그 인스턴스의 진단이 지금 돌고 있나 — 도는 동안 대화를 갈아치우면 진행 중 턴이 화면에서 사라진다 */
function chatRunningHere(instanceId) {
  return !!chat.running && chat.running.instanceId === instanceId;
}

/** 서버 턴 한 건 = 질문 한 줄 + 답 한 덩어리. 저장된 도구 호출에는 결과 본문이 없다(176절) — 화면은 원래 그리지 않는다 */
function chatTurnsFromServer(turns) {
  return (turns || []).flatMap((t) => [
    { role: "user", text: t.question },
    { role: "ai", status: "done", took: t.tookMs, steps: t.toolCalls || [], result: {
      aiEnabled: true, answer: t.answer, rootCause: t.rootCause, confidence: t.confidence,
      backend: t.backend, toolCallCount: (t.toolCalls || []).filter((c) => !c.rejected).length,
      toolCalls: t.toolCalls || [], note: null } },
  ]);
}

/** 목록의 짧은 시각 — 오늘이면 "오후 3:12", 아니면 "9월 14일". 서버 시각은 UTC라 브라우저 시간대로 옮긴다 */
function chatListTime(iso) {
  if (!iso) return "";
  const d = parseApiTime(String(iso));
  if (Number.isNaN(d.getTime())) return "";
  const now = new Date();
  if (d.getFullYear() !== now.getFullYear() || d.getMonth() !== now.getMonth() || d.getDate() !== now.getDate()) {
    return `${d.getMonth() + 1}월 ${d.getDate()}일`;
  }
  const h = d.getHours();
  return `${h < 12 ? "오전" : "오후"} ${h % 12 === 0 ? 12 : h % 12}:${String(d.getMinutes()).padStart(2, "0")}`;
}

// 답 서식 — 모델이 실제로 쓰는 것만 그린다: 빈 줄로 나뉜 문단, - / * / 1. 목록, `코드`, ``` 울타리 블록, **굵게**.
// 그 밖(표·#제목·링크)은 글자 그대로 둔다: 링크를 만들면 모델이 쓴 주소를 사람이 누르게 되어 피싱 경로가 되고,
// 표·제목은 지금 프롬프트 규약("JSON 하나만 출력")에서 실제로 나오지 않는다.
// 순서가 중요하다 — esc를 먼저 걸고 토큰만 감싼다. 뒤집으면 답에 섞인 태그가 그대로 실행된다.
function chatInline(escaped) {
  return escaped.replace(/`([^`]+)`|\*\*([^*]+)\*\*/g,
    (m, code, bold) => (code != null ? `<code>${code}</code>` : `<strong>${bold}</strong>`));
}

const CHAT_SQL_START = /^\s*(select|with|update|insert|delete|explain)\b/i;

function chatCodeHtml(code, lang) {
  // highlightSql도 토큰마다 esc를 거친다 — 서버가 준 SQL을 innerHTML에 넣어도 태그로 실행되지 않는다
  const sql = /^sql$/i.test(lang || "") || CHAT_SQL_START.test(code);
  return sql ? `<pre class="chat-sql"><code>${highlightSql(code)}</code></pre>`
             : `<pre><code>${esc(code)}</code></pre>`;
}

function chatAnswerHtml(text) {
  const src = stripEmoji(text ?? "");
  if (!src.trim()) return "";
  const lines = src.replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim().startsWith("```")) {
      const lang = line.trim().slice(3).trim();
      const buf = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith("```")) { buf.push(lines[i]); i++; }
      i++; // 닫는 울타리 — 없으면 끝까지가 블록이다
      out.push(chatCodeHtml(buf.join("\n"), lang));
      continue;
    }
    if (line.trim() === "") { i++; continue; }
    const ordered = /^\s*\d+\.\s/.test(line);
    if (ordered || /^\s*[-*]\s/.test(line)) {
      const items = [];
      while (i < lines.length) {
        const m = ordered ? lines[i].match(/^\s*\d+\.\s+(.*)$/) : lines[i].match(/^\s*[-*]\s+(.*)$/);
        if (!m) break;
        items.push(chatInline(esc(m[1])));
        i++;
      }
      out.push(ordered ? `<ol>${items.map((x) => `<li>${x}</li>`).join("")}</ol>`
                       : `<ul>${items.map((x) => `<li>${x}</li>`).join("")}</ul>`);
      continue;
    }
    const para = [];
    while (i < lines.length && lines[i].trim() !== "" && !lines[i].trim().startsWith("```")
           && !/^\s*(\d+\.|[-*])\s/.test(lines[i])) { para.push(lines[i]); i++; }
    out.push(`<p>${chatInline(esc(para.join("\n")))}</p>`);
  }
  return out.join("");
}

function chatToolHtml(c) {
  return `
    <div class="chat-tool${c.rejected ? " is-rejected" : ""}">
      <div class="chat-tool-line"><span class="chat-tool-dot" aria-hidden="true"></span><code class="chat-tool-name">${esc(c.tool)}</code>
        <span class="chat-tool-args" title="${esc(c.arguments || "")}">${esc(c.arguments || "")}</span></div>
      <div class="chat-tool-why">${c.rejected ? "거부됨 · " : ""}${esc(c.reason || "")}</div>
    </div>`;
}

/** 메타 줄의 도구 표기 — 중복 제거, 호출 순서. 한 번이라도 거부된 도구는 이름 뒤에 (거부) */
function chatToolNames(steps) {
  const names = [];
  for (const s of steps || []) {
    if (!s || !s.tool) continue;
    const found = names.find((n) => n.tool === s.tool);
    if (found) { found.rejected = found.rejected || !!s.rejected; continue; }
    names.push({ tool: s.tool, rejected: !!s.rejected });
  }
  return names;
}

function chatTurnHtml(t, idx) {
  if (t.role === "user") return `<div class="chat-msg chat-user"><div class="chat-bubble">${esc(t.text)}</div></div>`;
  if (t.role === "system") {
    const link = t.jobId ? ` <button class="chat-link" type="button" data-job="${esc(t.jobId)}">결과 보기</button>` : "";
    return `<div class="chat-msg chat-system">${esc(t.text)}${link}</div>`;
  }
  const steps = t.steps || [];
  // 진행 중에는 펼친 채로 쌓는다(그 순간 무엇을 보고 있는지가 정보) — 끝난 답은 기본으로 접는다
  const open = t.status === "running" || !!t.toolsOpen;
  const tools = steps.length
    ? `<div id="chat-tools-${idx}" class="chat-tools-used"${open ? "" : " hidden"}>${steps.map(chatToolHtml).join("")}</div>`
    : "";
  const secs = (ms) => `${Math.round(ms / 100) / 10}초`;
  let body = "";
  if (t.status === "running") {
    // 글자를 한 덩어리로 감싼다 — flex 줄에서는 글자 조각마다 gap이 끼어 "20 초"처럼 벌어졌다
    body = `<div class="chat-stage"><span class="chat-stage-dot" aria-hidden="true"></span><span>${esc(t.stage)} · <span data-elapsed>${Math.floor((Date.now() - t.startedAt) / 1000)}</span>초</span></div>`;
  } else if (t.status === "done") {
    const d = t.result;
    if (!d.aiEnabled) {
      body = `<div class="chat-note">${esc(d.note || "AI 진단을 쓸 수 없습니다")}</div>`;
    } else {
      const names = chatToolNames(steps);
      // backend(cli/api)는 메타 줄에서 뺀다 — 사용자가 판단에 쓸 값이 아니다(저장은 그대로 남는다)
      const toolsMeta = names.length
        ? ` · <button type="button" class="chat-meta-tools" data-idx="${idx}" aria-expanded="${open}" aria-controls="chat-tools-${idx}">도구 ${names.length}개 (${esc(names.map((n) => `${n.tool}${n.rejected ? " (거부)" : ""}`).join(", "))})</button>`
        : "";
      body = `
        ${d.rootCause ? `<p class="chat-root"><span class="chat-root-k">근본원인</span>${esc(stripEmoji(d.rootCause))}</p>` : ""}
        <div class="chat-text">${chatAnswerHtml(d.answer) || "<p>(답변 없음)</p>"}</div>
        <div class="chat-meta">확신도 ${esc(CHAT_CONFIDENCE[d.confidence] || d.confidence || "-")} · ${secs(t.took)}${toolsMeta}</div>
        ${d.note ? `<div class="chat-note">${esc(d.note)}</div>` : ""}`;
    }
  } else if (t.status === "stopped") {
    body = `<div class="chat-meta">화면에서 중지했습니다 · ${secs(t.took)}</div>`;
  } else if (t.status === "error") {
    body = `<div class="chat-error">진단하지 못했습니다: ${esc(t.error)}</div>`;
  }
  return `<div class="chat-msg chat-ai">${tools}${body}</div>`;
}

function renderChat({ follow = false } = {}) {
  const log = $("#chat-log");
  if (!log) return;
  const nearBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 48;
  const inst = state.instance;
  const cs = chatState();
  if (!inst || !cs) {
    // 인스턴스가 한 대도 없을 때 여기서 또 말하면 화면 세 곳이 같은 문장이 된다 — 그 사실은 작업면 한 줄과 부제가 말한다(B6fix)
    log.innerHTML = state.instances.length
      ? '<div class="chat-empty"><p>왼쪽에서 인스턴스를 고르면 그 DB에 물어볼 수 있습니다.</p></div>'
      : "";
  } else {
    const parts = [];
    if (cs.error) parts.push(`<div class="chat-error">${esc(cs.error)}</div>`);
    if (!cs.turns.length) {
      // 불러오는 중에도 빈 대화 화면을 함께 보여준다 — 로딩 문구만 남으면 사람은 기다릴지 새로 고를지 알 수 없다.
      // 서버가 느릴 때(대상 조회가 몰리면 대화 목록 조회가 그 뒤에 줄을 선다) 실제로 십여 초가 걸린다
      const loading = cs.status === "loading"
        ? `<p class="chat-empty-sub">대화를 불러오는 중입니다.</p>` : "";
      parts.push(`
          <div class="chat-empty">
            ${loading}
            <p><strong>${esc(inst.name)}</strong>에 무엇이든 물어보세요.</p>
            <p class="chat-empty-sub">근거가 없으면 모른다고 답합니다.</p>
            <div class="chat-suggest">${CHAT_SUGGESTIONS.map((q) => `<button type="button" class="chat-chip" data-q="${esc(q)}">${esc(q)}</button>`).join("")}</div>
          </div>`);
    } else {
      parts.push(cs.turns.map(chatTurnHtml).join(""));
    }
    log.innerHTML = parts.join("");
  }
  // 새 줄이 붙으면 맨 아래로 — 단 위로 올려 앞 대화를 읽는 중이면 끌어내리지 않는다
  if (follow || nearBottom) log.scrollTop = log.scrollHeight;
  syncChatComposer();
  syncChatHeader();
}

/** 헤더 — 부제는 대상, 전환 버튼 글자는 지금 대화 제목(없으면 "새 대화") */
function syncChatHeader() {
  const inst = state.instance;
  const cs = chatState();
  const sub = $("#chat-sub");
  const label = $("#chat-switch-label");
  const sw = $("#chat-switch");
  if (sub) {
    // 이름과 고정 문구를 분리한다 — 좁아지면 이름만 줄어들고 "· 읽기 도구로만 답합니다"는 끝까지 남는다.
    // 문구 사이의 공백은 글자에 넣지 않고 CSS 여백으로 준다(flex 항목 경계에서 앞 공백이 사라진다)
    // 등록된 인스턴스가 한 대도 없으면 "왼쪽에서 고르세요"가 할 수 없는 일을 권하는 문장이 된다(B6)
    const chatIdle = state.instances.length ? "왼쪽에서 인스턴스를 고르세요" : "인스턴스가 등록되면 이 칸에서 물어볼 수 있습니다";
    sub.innerHTML = inst
      ? `<span class="chat-sub-name">${esc(inst.name)}</span><span class="chat-sub-fixed">· 읽기 도구로만 답합니다</span>`
      : `<span class="chat-sub-name">${chatIdle}</span>`;
  }
  if (label) label.textContent = cs?.title || "새 대화";
  if (sw) {
    sw.disabled = !inst;
    sw.hidden = !inst;   // 누를 수 없으면 감춘다(B6fix) — 대상이 없으면 새 대화도 전환도 할 일이 없다
  }
}

function renderChatList() {
  const box = $("#chat-list-items");
  const cs = chatState();
  if (!box) return;
  if (!cs || !cs.list.length) {
    box.innerHTML = `<div class="chat-list-empty">아직 대화가 없습니다</div>`;
    return;
  }
  box.innerHTML = cs.list.map((c) => {
    // 삭제는 그 자리에서 한 번 더 묻는다 — window.confirm은 무엇을 지우는지 화면에서 사라진다
    if (chat.confirmId === c.id) {
      return `<div class="chat-list-confirm">
        <span>삭제할까요?</span>
        <button type="button" class="chat-list-yes" data-del="${esc(c.id)}">삭제</button>
        <button type="button" class="chat-list-no" data-cancel="1">취소</button>
      </div>`;
    }
    const sel = c.id === cs.conversationId ? " sel" : "";
    return `<div class="chat-list-row${sel}">
      <button type="button" class="chat-list-item" data-open="${esc(c.id)}">
        <span class="chat-list-title">${esc(c.title)}</span>
        <span class="chat-list-time">${esc(chatListTime(c.updatedAt))}</span>
      </button>
      <button type="button" class="chat-list-del" data-confirm="${esc(c.id)}" aria-label="대화 삭제">
        <svg viewBox="0 0 14 14" width="12" height="12" aria-hidden="true">
          <path d="M3 4h8M5.6 4V3h2.8v1M4.2 4l.5 7h4.6l.5-7" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
      </button>
    </div>`;
  }).join("");
}

function openChatList() {
  const list = $("#chat-list");
  const sw = $("#chat-switch");
  if (!list || !sw) return;
  chat.confirmId = null;
  renderChatList();
  list.hidden = false;
  sw.setAttribute("aria-expanded", "true");
}

function closeChatList() {
  const list = $("#chat-list");
  const sw = $("#chat-switch");
  if (!list || !sw || list.hidden) return;
  list.hidden = true;
  sw.setAttribute("aria-expanded", "false");
  chat.confirmId = null;
}

/** 목록만 다시 받는다(첫 턴이 서버에서 제목을 바꾼다). 실패는 대화 칸 한 줄로 — 화면 전체를 막지 않는다 */
async function loadConversations() {
  const inst = state.instance;
  const cs = chatState();
  if (!inst || !cs) return;
  try {
    const list = await api(`/api/instances/${inst.id}/conversations`);
    if (state.instance?.id !== inst.id) return;
    cs.list = list;
    const mine = cs.conversationId == null ? null : list.find((c) => c.id === cs.conversationId);
    if (mine) cs.title = mine.title;
    cs.error = null;
  } catch (e) {
    if (state.instance?.id !== inst.id) return;
    cs.error = `대화 목록을 불러오지 못했습니다: ${e.message}`;
  }
  renderChat();
  renderChatList();
}

/** 인스턴스를 고를 때 — 그 인스턴스의 대화를 서버에서 다시 읽는다(있으면 열던 대화, 없으면 가장 최근) */
async function loadConversationsAndOpenLatest(instanceId) {
  const cs = chatState();
  if (!cs || chatRunningHere(instanceId)) return;
  cs.status = "loading";
  cs.error = null;
  renderChat();
  await loadConversations();
  if (state.instance?.id !== instanceId) return;
  if (cs.conversationId != null) {
    await openConversation(cs.conversationId);
  } else if (cs.list.length) {
    await openConversation(cs.list[0].id);
  } else {
    cs.status = "idle";
    renderChat();
  }
}

async function openConversation(cid) {
  const inst = state.instance;
  const cs = chatState();
  if (!inst || !cs || chatRunningHere(inst.id)) return;
  closeChatList();
  cs.status = "loading";
  cs.error = null;
  cs.conversationId = cid;
  cs.title = "";
  cs.turns = [];
  renderChat();
  let detail;
  try {
    detail = await api(`/api/instances/${inst.id}/conversations/${cid}`);
  } catch (e) {
    if (state.instance?.id !== inst.id) return;
    cs.status = "idle";
    if (/^404\b/.test(e.message)) {
      // 지워진 대화다 — 오류가 아니라 빈 대화로 돌아간다
      cs.conversationId = null;
      cs.list = cs.list.filter((c) => c.id !== cid);
    } else {
      cs.error = `대화를 열지 못했습니다: ${e.message}`;
    }
    renderChat();
    renderChatList();
    return;
  }
  if (state.instance?.id !== inst.id) return;
  cs.status = "idle";
  cs.title = detail.title;
  cs.turns = chatTurnsFromServer(detail.turns);
  renderChat({ follow: true });
  renderChatList();
}

/** 새 대화 — 서버에는 아직 만들지 않는다. 첫 질문을 보낼 때 POST한다(빈 대화가 목록에 쌓이지 않게) */
function newConversation() {
  const inst = state.instance;
  const cs = chatState();
  if (!inst || !cs || chatRunningHere(inst.id)) return;
  closeChatList();
  cs.conversationId = null;
  cs.title = "";
  cs.turns = [];
  cs.error = null;
  cs.status = "idle";
  renderChat();
  renderChatList();
  $("#diagnose-question").focus();
}

async function deleteConversation(cid) {
  const inst = state.instance;
  const cs = chatState();
  if (!inst || !cs) return;
  chat.confirmId = null;
  try {
    await api(`/api/instances/${inst.id}/conversations/${cid}`, { method: "DELETE" });
  } catch (e) {
    if (!/^404\b/.test(e.message)) {
      cs.error = `대화를 지우지 못했습니다: ${e.message}`;
      renderChat();
      renderChatList();
      return;
    }
  }
  if (state.instance?.id !== inst.id) return;
  cs.list = cs.list.filter((c) => c.id !== cid);
  if (cs.conversationId === cid) {
    cs.conversationId = null;
    cs.title = "";
    cs.turns = [];
  }
  cs.error = null;
  renderChat();
  renderChatList();
}

function syncChatComposer() {
  const input = $("#diagnose-question");
  const send = $("#btn-diagnose");
  const openBtn = $("#btn-aiop-open");
  const submit = $("#btn-aiop-submit");
  if (!input) return;
  const inst = state.instance;
  const runningHere = inst && chatRunningHere(inst.id);
  const runningElsewhere = chat.running && !runningHere;
  const hasText = input.value.trim().length > 0;
  input.disabled = !inst;
  // 0대일 때 "왼쪽에서 고르세요"는 할 수 없는 일이다 — 부제가 사실을 말하므로 여기서는 비운다(B6fix)
  input.placeholder = inst ? "무엇이 궁금한가요?"
    : state.instances.length ? "왼쪽에서 인스턴스를 고르면 물어볼 수 있습니다" : "";
  // 누를 수 없는 버튼은 감춘다 — 목록의 "새 대화"는 대상이 없으면 만들 것이 없다(B6fix)
  const newBtn = $("#chat-new");
  if (newBtn) newBtn.hidden = !inst;
  send.classList.toggle("is-stop", !!runningHere);
  send.setAttribute("aria-label", runningHere ? "중지" : "보내기");
  send.title = runningElsewhere ? "다른 인스턴스의 진단이 끝나면 보낼 수 있습니다" : "";
  // 진행 중에는 같은 버튼이 중지가 된다 — 그래서 빈 입력이어도 누를 수 있어야 한다
  send.disabled = runningHere ? false : (!inst || !hasText || !!runningElsewhere);
  if (openBtn) openBtn.disabled = !inst || !hasText;
  if (submit && !submit.dataset.busy) submit.disabled = !inst || !hasText;
}

function autoGrowChatInput() {
  const input = $("#diagnose-question");
  input.style.height = "auto";
  input.style.height = `${Math.min(input.scrollHeight, 148)}px`;
  // 최대 높이 전에는 스크롤을 숨긴다 — 한 줄인데도 1~2px 차이로 회색 스크롤바가 떠 있었다
  input.style.overflowY = input.scrollHeight > 148 ? "auto" : "hidden";
}

async function runDiagnose() {
  const input = $("#diagnose-question");
  const inst = state.instance;
  const cs = chatState();
  const question = input.value.trim();
  if (!inst || !cs || !question || chat.running) return;

  const turns = cs.turns;
  turns.push({ role: "user", text: question });
  const turn = { role: "ai", status: "running", stage: "진단을 시작합니다", steps: [], startedAt: Date.now() };
  turns.push(turn);
  input.value = "";
  autoGrowChatInput();
  closeChatList();

  const controller = new AbortController();
  chat.running = { instanceId: inst.id, controller };
  renderChat({ follow: true });
  // 경과 초만 1초마다 고친다 — 전체를 다시 그리면 읽던 자리의 선택·스크롤이 흔들린다
  const ticker = setInterval(() => {
    if (state.instance?.id !== inst.id) return;
    const el = document.querySelector("#chat-log [data-elapsed]");
    if (el) el.textContent = Math.floor((Date.now() - turn.startedAt) / 1000);
  }, 1000);
  const redraw = () => { if (state.instance?.id === inst.id) renderChat(); };

  try {
    // 첫 질문을 보낼 때 대화를 만든다 — 들어가기만 해도 대화가 생기면 목록이 빈 대화로 찬다
    if (cs.conversationId == null) {
      const created = await api(`/api/instances/${inst.id}/conversations`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: "{}",
      });
      cs.conversationId = created.id;
      cs.title = created.title;
    }
    let result = null;
    // 본문에 history를 싣지 않는다 — 앞선 맥락은 서버가 이 대화의 턴에서 만든다(위조 방지, 176절)
    await streamSse(`/api/instances/${inst.id}/diagnose/stream`,
      { question, conversationId: cs.conversationId }, (name, data) => {
        if (name === "thinking") {
          turn.stage = data.synthesis ? "도구 호출 상한에 도달해 지금까지의 근거로 답을 정리하는 중" : `${data.step}번째 판단 중`;
        } else if (name === "tool") {
          turn.steps.push(data);
          turn.stage = data.rejected ? `요청한 도구가 거부됐습니다: ${data.tool}` : `${data.tool} 결과를 받았습니다`;
        } else if (name === "result") {
          result = data;
          return;
        } else if (name === "error") {
          throw new Error(data.message);
        }
        redraw();
      }, controller.signal);
    if (!result) throw new Error("답이 끝까지 오지 않았습니다(연결 끊김)");
    turn.status = "done";
    turn.result = result;
    turn.steps = result.toolCalls || turn.steps;
  } catch (e) {
    if (e.name === "AbortError") {
      turn.status = "stopped";
    } else {
      turn.status = "error";
      turn.error = e.message;
    }
  } finally {
    turn.took = Date.now() - turn.startedAt;
    clearInterval(ticker);
    chat.running = null;
    redraw();
    syncChatComposer();
    // 첫 턴이 저장되면 서버가 제목을 질문 앞 40자로 바꾼다 — 목록을 다시 받아 그 제목을 보여준다
    if (state.instance?.id === inst.id) loadConversations();
  }
}

/** 도구 줄 접기/펴기 — 다시 그리지 않고 그 자리만 바꾼다(누른 버튼에 초점이 남는다) */
function toggleChatTools(button) {
  const body = document.getElementById(button.getAttribute("aria-controls"));
  if (!body) return;
  const wasOpen = button.getAttribute("aria-expanded") === "true";
  button.setAttribute("aria-expanded", String(!wasOpen));
  body.hidden = wasOpen;
  const turn = chatTurns()[Number(button.dataset.idx)];
  if (turn) turn.toolsOpen = !wasOpen;
}

function openAiOpPopover() {
  const popover = $("#aiop-popover");
  const openBtn = $("#btn-aiop-open");
  if (!popover || popover.hidden === false) return;
  popover.hidden = false;
  openBtn.setAttribute("aria-expanded", "true");
  $("#aiop-new-type").focus();
}

function closeAiOpPopover(refocus = false) {
  const popover = $("#aiop-popover");
  const openBtn = $("#btn-aiop-open");
  if (!popover || popover.hidden) return;
  popover.hidden = true;
  openBtn.setAttribute("aria-expanded", "false");
  if (refocus) openBtn.focus();
}

function wireChat() {
  const form = $("#chat-form");
  const input = $("#diagnose-question");
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    if (chat.running && chat.running.instanceId === state.instance?.id) chat.running.controller.abort();
    else runDiagnose();
  });
  input.addEventListener("keydown", (e) => {
    // 한글은 조합 중에도 Enter가 온다 — 그때 보내면 마지막 글자가 덜 쳐진 채 나간다
    if (e.key !== "Enter" || e.shiftKey || e.isComposing || e.keyCode === 229) return;
    e.preventDefault();
    if (!chat.running) runDiagnose();
  });
  input.addEventListener("input", () => { autoGrowChatInput(); syncChatComposer(); });

  $("#chat-switch").addEventListener("click", () => {
    if ($("#chat-list").hidden) { openChatList(); loadConversations(); }
    else closeChatList();
  });
  $("#chat-new").addEventListener("click", newConversation);
  $("#chat-list").addEventListener("click", (e) => {
    // 목록 안의 클릭은 여기서 끝낸다. 삭제 확인으로 목록을 다시 그리면 누른 요소가 DOM에서 떨어져
    // 바깥 클릭 판정(e.target.closest)이 "바깥"으로 읽고 패널을 닫아 버린다
    e.stopPropagation();
    const open = e.target.closest("[data-open]");
    if (open) { openConversation(Number(open.dataset.open)); return; }
    const ask = e.target.closest("[data-confirm]");
    if (ask) { chat.confirmId = Number(ask.dataset.confirm); renderChatList(); return; }
    if (e.target.closest("[data-cancel]")) { chat.confirmId = null; renderChatList(); return; }
    const yes = e.target.closest("[data-del]");
    if (yes) deleteConversation(Number(yes.dataset.del));
  });
  // 바깥 클릭·Esc로 닫힌다. Esc는 초점을 전환 버튼으로 돌려준다(키보드로 연 사람이 길을 잃지 않게)
  document.addEventListener("click", (e) => {
    if (!$("#chat-list").hidden && !e.target.closest("#chat-list") && !e.target.closest("#chat-switch")) closeChatList();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("#chat-list").hidden) { closeChatList(); $("#chat-switch").focus(); }
  });

  $("#chat-log").addEventListener("click", (e) => {
    const chip = e.target.closest(".chat-chip");
    if (chip) {
      input.value = chip.dataset.q;
      autoGrowChatInput();
      syncChatComposer();
      input.focus();
      return;
    }
    const toggle = e.target.closest(".chat-meta-tools");
    if (toggle) {
      toggleChatTools(toggle);
      return;
    }
    const job = e.target.closest(".chat-link[data-job]");
    if (job) {
      document.querySelector('.tab[data-tab="monitor"]')?.click();
      showMonGroup("diag");
      loadAiOperations().then(() => openAiOperation(job.dataset.job));
      document.querySelector(".aiops-card")?.scrollIntoView({ block: "start" });
    }
  });

  // 작업 맡기기 팝오버 — 늘 떠 있던 선택 상자 둘을 여기로 접었다(무엇인지 알 수 없었다)
  $("#btn-aiop-open").addEventListener("click", openAiOpPopover);
  $("#btn-aiop-cancel").addEventListener("click", () => closeAiOpPopover(true));
  $("#aiop-popover").addEventListener("keydown", (e) => { if (e.key === "Escape") closeAiOpPopover(true); });

  // 데이터 보호 한 줄 — hover만 있는 네이티브 title은 키보드 사용자에게 없는 정보가 된다
  const privacy = $("#chat-privacy");
  const privacyTip = $("#chat-privacy-tip");
  const showTip = () => { privacyTip.hidden = false; };
  const hideTip = () => { privacyTip.hidden = true; };
  privacy.addEventListener("mouseenter", showTip);
  privacy.addEventListener("mouseleave", hideTip);
  privacy.addEventListener("focus", showTip);
  privacy.addEventListener("blur", hideTip);
  privacy.addEventListener("keydown", (e) => { if (e.key === "Escape") hideTip(); });

  renderChat();
  renderChatList();
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
      syncLive();
    });
  });
}

// Monitoring 카테고리 서브내비 — 한 번에 한 그룹만. 로딩은 selectInstance가 전부 미리 하므로 순수 표시 전환.
function setupMonitorNav() {
  document.querySelectorAll(".mon-tab").forEach((tab) => {
    tab.addEventListener("click", () => showMonGroup(tab.dataset.mon));
  });
}

// 특정 카테고리 그룹을 연다(딥링크가 숨은 그룹의 섹션으로 스크롤할 때도 씀)
function showMonGroup(name) {
  document.querySelectorAll(".mon-tab").forEach((t) => t.classList.toggle("active", t.dataset.mon === name));
  document.querySelectorAll(".mon-group").forEach((g) => { g.hidden = g.dataset.group !== name; });
  syncLive();
  scheduleAiOpsRefresh();
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

// ---------- 한 셸의 두 모드(149절) — 관제와 워크벤치를 페이지 이동 없이 전환한다 ----------
// 워크벤치 코드는 처음 워크벤치 모드로 들어갈 때 모듈로 불러온다(관제만 보는 사람은 받지 않는다)
const shell = { mode: "monitor", workbench: null };

function setupModes() {
  document.querySelectorAll(".mode-tab").forEach((b) => b.addEventListener("click", () => setMode(b.dataset.mode)));
  if (new URLSearchParams(location.search).get("mode") === "workbench") setMode("workbench", { keepUrl: true });
  window.addEventListener("popstate", () => {
    const m = new URLSearchParams(location.search).get("mode") === "workbench" ? "workbench" : "monitor";
    if (m !== shell.mode) setMode(m, { keepUrl: true });
  });
}

async function setMode(mode, { instance = null, handoff = null, keepUrl = false } = {}) {
  const toWorkbench = mode === "workbench";
  shell.mode = toWorkbench ? "workbench" : "monitor";
  document.querySelectorAll(".mode-tab").forEach((b) => {
    const on = b.dataset.mode === shell.mode;
    b.classList.toggle("active", on);
    b.setAttribute("aria-selected", String(on));
  });
  $("#mode-monitor").hidden = toWorkbench;
  $("#mode-workbench").hidden = !toWorkbench;
  document.body.classList.toggle("mode-workbench", toWorkbench);
  // 숨긴 관제 화면의 실시간 연결은 닫는다 — 안 보는 화면이 구독자로 남으면 대상 조회가 멈추지 않는다(AGENTS.md 화면 규칙)
  syncLive();
  if (!keepUrl) {
    const q = new URLSearchParams();
    const id = instance ?? (toWorkbench ? shell.workbench?.currentInstanceId?.() : null) ?? state.instance?.id;
    if (toWorkbench) q.set("mode", "workbench");
    if (id != null) q.set("instance", id);
    if (handoff) q.set("handoff", handoff);
    history.pushState(null, "", q.toString() ? `/?${q}` : "/");
  }
  if (!toWorkbench) {
    openMonitorInstance(shell.workbench?.currentInstanceId?.());
    return;
  }
  if (!can("WORKBENCH")) {
    showWorkbenchGuard();
    return;
  }
  if (!shell.workbench) {
    shell.workbench = await import("./workbench/main.js");
    await shell.workbench.mount(); // URL의 instance·sheet·ticket·handoff를 읽어 시작한다
  } else if (instance != null || handoff) {
    await shell.workbench.navigate({ instance, handoff });
  }
}

// 워크벤치에서 보던 인스턴스를 관제로 돌아와서도 연다 — 모드를 바꿀 때마다 대상을 다시 고르지 않게
function openMonitorInstance(id) {
  if (id == null || !state.instances || (state.instance && String(state.instance.id) === String(id))) return;
  const inst = state.instances.find((i) => String(i.id) === String(id));
  if (!inst) return;
  selectInstance(inst, $(`#instance-list .instance-card[data-id="${inst.id}"]`));
}

// 관제만 보는 역할이 워크벤치 주소로 들어오면 빈 화면 대신 갈 곳을 알려준다 — 서버도 워크벤치 API를 403으로 막는다
function showWorkbenchGuard() {
  const view = $("#mode-workbench");
  view.innerHTML = `<main style="padding:48px 16px">
    <div class="wb-note" style="margin:0 auto;max-width:640px">
      <p>워크벤치는 조회 계정으로 대상 DB의 행 값을 보는 화면이라 요청자(REQUESTER) 이상 역할이 필요합니다.
        지금 역할(${esc(ROLE_LABEL[state.role] || state.role || "")})은 관제 모드에서 지표·리포트를 봅니다.</p>
      <p><button class="btn btn-primary btn-small" type="button" data-mode-go="monitor">관제로</button></p></div></main>`;
  view.querySelector("[data-mode-go]").addEventListener("click", () => setMode("monitor"));
}

document.addEventListener("DOMContentLoaded", async () => {
  // 세션 kill 버튼 노출 여부가 역할에 달려 있어, 인스턴스 로딩(→세션 표) 전에 역할을 먼저 확정한다
  await loadMe();
  setupModes();
  loadMcpCommand();
  // 함대 카드 둘은 "등록된 인스턴스가 있는가"에 따라 문장이 달라진다 — 목록을 안 뒤에 부른다(B6).
  // 병렬로 쏘면 목록보다 먼저 도착한 카드가 "등록된 인스턴스가 없습니다"로 굳는다
  loadInstances().catch(() => {}).then(() => {
    loadHealthScore();     // 함대 전체 통합 헬스 스코어 (D8) — 나쁜 순 정렬, 대시보드 상단 상시 뷰
    loadBackupFreshness(); // 함대 전체 백업 신선도 (D7) — 인스턴스 선택과 무관한 상시 뷰
  });
  setupTabs();
  setupMonitorNav();
  setupLive();
  setupTooltip();        // 네이티브 title을 예쁜 커스텀 툴팁으로 자동 승격(전역 위임)
  setupSqlTip();         // 표의 SQL 셀은 강조된 전용 툴팁을 쓴다(B3) — title을 쓰지 않는다
  setupInstanceFilter(); // 검색·필터 이벤트 연결(검색·필터 구동 렌더)
  setupPresets();
  setupAiOperations();
  // SQL 편집 시 하이라이트 레이어를 따라 갱신·스크롤 동기화(투명 textarea 오버레이)
  $("#detail-sql").addEventListener("input", updateSqlHl);
  $("#detail-sql").addEventListener("scroll", () => { const h = $("#detail-sql-hl"); if (h) { h.scrollTop = $("#detail-sql").scrollTop; h.scrollLeft = $("#detail-sql").scrollLeft; } });
  setupChartDrag();
  setupCopyButtons();
  setupQueryDetail();
  watchRowDetails();   // 표 행 안 상세의 폭을 스크롤 상자의 보이는 폭에 맞춘다(B6fix)
  setupMcpCard();      // 제공 도구 접기(B4)
  setupUsersCard();    // 역할 적용/취소·새 사용자 조건(B4)
  loadMcpTools();
  $("#btn-query").addEventListener("click", runQuery);
  $("#btn-compare").addEventListener("click", runCompare);
  // 관제에서 본 쿼리를 요청자가 워크벤치의 새 워크시트로 가져간다(행 값 조회는 워크벤치의 조회 계정·마스킹을 거친다)
  $("#btn-to-workbench").addEventListener("click", () => handToWorkbench("sql", $("#detail-sql").value.trim()));
  $("#btn-user-create").addEventListener("click", createUser);
  $("#btn-schema-diff").addEventListener("click", runSchemaDiff);
  $("#btn-param-diff").addEventListener("click", runParamDiff);
  $("#btn-config-drift").addEventListener("click", loadConfigDrift);
  $("#btn-review-submit").addEventListener("click", submitReview);
  $("#btn-incident").addEventListener("click", generateIncident);
  $("#btn-incident-dl").addEventListener("click", downloadIncident);
  $("#btn-monthly").addEventListener("click", generateMonthly);
  $("#btn-monthly-dl").addEventListener("click", downloadMonthly);
  incidentDefaults();
  $("#btn-ddl-noop").addEventListener("click", () => runOnlineDdl(false));
  $("#btn-ddl-exec").addEventListener("click", () => runOnlineDdl(true));
  wireChat();
  $("#audit-search-btn").addEventListener("click", loadAudit);
  $("#audit-reset-btn").addEventListener("click", () => {
    ["audit-principal", "audit-action", "audit-outcome"].forEach((id) => { $(`#${id}`).value = ""; });
    loadAudit();
  });
});

// 쿼리 상세의 조작 계층 (B3) — 토글 다섯·더보기 메뉴·다시 조회·SQL ID 복사·안티패턴 접기.
// 버튼은 id로 하나씩 잡는다: 토글 묶음이 헤더에서 줄바꿈돼도 리스너는 그대로 살아 있다.
function setupQueryDetail() {
  Object.keys(DETAIL_VIEWS).forEach((key) => {
    $("#btn-" + key).addEventListener("click", () => toggleDetailView(key));
  });
  // 섹션 제목의 "다시 조회"는 위임으로 잡는다 — 결과를 다시 그려도 버튼이 새로 생기지 않는다
  $("#query-detail").addEventListener("click", (e) => {
    const refresh = e.target.closest("[data-refresh]");
    if (refresh) refreshDetailView(refresh.dataset.refresh);
  });
  // 더보기 — 항목은 실행이 아니라 섹션 열기다(실행은 그 안의 주 버튼이 한다)
  $("#btn-detail-more").addEventListener("click", () => {
    if ($("#detail-more-menu").hidden) openDetailMore(); else closeDetailMore();
  });
  $("#btn-deep").addEventListener("click", () => openDetailSection("#deep-section"));
  $("#btn-inquiry").addEventListener("click", () => openDetailSection("#inquiry-section"));
  $("#btn-deep-run").addEventListener("click", runDeepDiagnose);
  $("#btn-inquiry-send").addEventListener("click", runInquiry);
  document.addEventListener("click", (e) => {
    if (!$("#detail-more-menu").hidden && !e.target.closest(".detail-more-wrap")) closeDetailMore();
  });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("#detail-more-menu").hidden) closeDetailMore(true);
  });
  $("#btn-advisor-run").addEventListener("click", runIndexAdvisor);
  // 안티패턴 "다른 쿼리 N개 보기" — 결과를 다시 그리므로 위임으로 잡는다
  $("#antipattern-result").addEventListener("click", (e) => {
    const more = e.target.closest(".ap-more");
    if (!more) return;
    const box = document.getElementById(more.getAttribute("aria-controls"));
    const open = more.getAttribute("aria-expanded") === "true";
    more.setAttribute("aria-expanded", String(!open));
    box.hidden = open;
    more.textContent = open ? `다른 쿼리 ${more.dataset.more}개 보기` : "접기";
  });
  // SQL ID 복사 — 화면에 보이는 것은 짧은 값이라 전체 값은 상태에서 꺼낸다
  $("#btn-copy-qid").addEventListener("click", async () => {
    const full = state.currentQuery ? String(state.currentQuery.queryId ?? "") : "";
    const btn = $("#btn-copy-qid");
    if (!full) return;
    try {
      await navigator.clipboard.writeText(full);
      btn.textContent = "복사됨";
      setTimeout(() => { btn.textContent = "복사"; }, 1500);
    } catch (e) { /* http 컨텍스트 등 클립보드 불가 환경 — 버튼은 그대로 둔다 */ }
  });
}

// 워크벤치는 별도 ES 모듈이라 이 파일의 함수를 직접 부를 수 없다 — 드롭다운을 복제하지 않게 하나만 내보낸다(B5).
// 관제의 인스턴스 필터와 워크벤치의 인스턴스 고르기가 같은 모양·동작이어야 한다.
window.dbtowerEnhanceSelect = enhanceSelect;
