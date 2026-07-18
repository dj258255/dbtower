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
    return r.json();
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
  currentQuery: null,  // 상세 패널에 열린 쿼리
  lastPlan: null,      // 마지막 EXPLAIN 실행계획 (문의 첨부용)
  lastFindings: [],    // 마지막 규칙 기반 지적
  lastAi: null,        // 마지막 AI 분석
  role: null,          // 로그인 주체의 역할 (ADMIN이면 세션 kill 버튼 노출)
};

// ---------- 유틸 ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

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
const fmtNum = (v, digits = 2) => v == null ? "-" : Number(v).toLocaleString("ko-KR", { maximumFractionDigits: digits });

// 바이트를 사람이 읽는 단위로 (파티션 크기 등). 1024 진법, 소수 한 자리.
const fmtBytes = (v) => {
  if (v == null) return "-";
  let n = Number(v);
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

// ---------- 인스턴스 ----------
async function loadInstances() {
  const list = await api("/api/instances");
  state.instances = list;
  populateSchemaSelects(list); // Schema Diff 좌/우 드롭다운 채우기
  const box = $("#instance-list");
  if (!list.length) {
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다 — POST /api/instances 로 등록하세요.</div>';
    return;
  }
  // 서버 공유 인지 (Phase 4): 같은 host:port에 등록된 DB들은 같은 서버 — 서버 전역 경보(복제·세션·
  // 데드락)가 그룹당 1회로 dedup되므로, 어느 카드들이 한 서버인지 눈에 보여야 경보를 옳게 해석한다.
  const serverCount = {};
  list.forEach((i) => {
    const key = `${i.host.toLowerCase()}:${i.port}`;
    serverCount[key] = (serverCount[key] || 0) + 1;
  });
  box.innerHTML = list.map((i) => {
    const serverKey = `${i.host.toLowerCase()}:${i.port}`;
    const sharedNames = serverCount[serverKey] > 1
      ? list.filter((o) => o.id !== i.id && `${o.host.toLowerCase()}:${o.port}` === serverKey).map((o) => o.name)
      : [];
    return `
    <div class="instance-card" data-id="${i.id}" data-name="${esc(i.name.toLowerCase())}"
         data-host="${esc(i.host.toLowerCase())}" data-type="${esc(i.type)}" data-team="${esc(i.teamLabel || "")}"
         data-env="${esc(i.environment || "")}" data-region="${esc(i.region || "")}" data-cluster="${esc(i.cluster || "")}">
      <div class="instance-name">
        <span class="type-badge type-${esc(i.type)}">${esc(i.type)}</span>
        ${esc(i.name)}
        <span class="repl-role" id="role-${i.id}"></span>
        <span class="health-dot" id="health-${i.id}"></span>
        <button class="collect-toggle ${i.collectionEnabled ? "" : "isolated"}" data-id="${i.id}"
          title="수집 격리 토글 — 끄면 스냅샷 수집·운영 경보에서 이 인스턴스를 뺀다(등록은 유지)">
          ${i.collectionEnabled ? "수집중" : "격리됨"}</button>
      </div>
      <div class="instance-host">${esc(i.host)}:${i.port} / ${esc(i.dbName)}
        ${sharedNames.length ? `<span class="server-shared-badge"
          title="같은 서버(${esc(serverKey)})에 등록된 다른 인스턴스: ${esc(sharedNames.join(", "))} — 서버 전역 경보(복제·세션·데드락)는 그룹당 1회">서버 공유 ×${serverCount[serverKey]}</span>` : ""}
        <span class="health-ms" id="healthms-${i.id}"></span></div>
      ${i.environment || i.region || i.cluster || i.teamLabel || i.consoleUrl ? `<div class="instance-meta">
        ${i.environment ? `<span class="tag-badge tag-env" title="환경">${esc(i.environment)}</span>` : ""}
        ${i.region ? `<span class="tag-badge tag-region" title="리전">${esc(i.region)}</span>` : ""}
        ${i.cluster ? `<span class="tag-badge tag-cluster" title="클러스터">${esc(i.cluster)}</span>` : ""}
        ${i.teamLabel ? `<span class="team-badge" title="담당 팀/Slack">${esc(i.teamLabel)}</span>` : ""}
        ${i.consoleUrl && /^https?:\/\//.test(i.consoleUrl) ? `<a class="console-link" href="${esc(i.consoleUrl)}" target="_blank" rel="noopener" title="콘솔 딥링크(PI·Grafana 등)">콘솔 ↗</a>` : ""}
      </div>` : ""}
    </div>`;
  }).join("");

  box.querySelectorAll(".instance-card").forEach((card) => {
    card.addEventListener("click", () => selectInstance(list.find((i) => i.id == card.dataset.id), card));
  });
  // 수집 격리 토글 — 카드 선택과 분리(stopPropagation). PATCH 후 목록을 다시 그려 상태 반영.
  box.querySelectorAll(".collect-toggle").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      const inst = list.find((i) => i.id == id);
      try {
        await api(`/api/instances/${id}/collection`, {
          method: "PATCH", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ enabled: !inst.collectionEnabled }),
        });
        loadInstances();
      } catch (err) { alert("수집 토글 실패: " + err.message); }
    });
  });
  // 첫 인스턴스 자동 선택 — 진입 즉시 대시보드가 차 있게
  // 진단 딥링크 (심화 아크 5) — 알림의 "진단" 링크로 열리면 해당 인스턴스를 선택하고
  // Monitoring 탭의 자연어 진단 입력을 프리필한다(실행은 사람이 — 열리자마자 AI를 돌리지 않는다)
  const params = new URLSearchParams(location.search);
  const deepId = params.get("instance");
  const deepQ = params.get("diagnose");
  const deepView = params.get("view");
  const target = deepId ? list.find((i) => String(i.id) === deepId) : null;
  const first = box.querySelector(".instance-card");
  if (target) {
    selectInstance(target, box.querySelector(`.instance-card[data-id="${target.id}"]`) ?? first);
    if (deepQ) {
      document.querySelector('.tab[data-tab="monitor"]').click();
      showMonGroup("perf"); // 자연어 진단은 성능 그룹
      const input = $("#diagnose-question");
      input.value = deepQ;
      input.scrollIntoView({ block: "center" });
      input.focus();
    }
    // 설정 드리프트 알림의 "변경 이력 보기" 링크 — Monitoring 탭 열고 이력을 바로 조회
    if (deepView === "config-drift") {
      document.querySelector('.tab[data-tab="monitor"]').click();
      showMonGroup("gov"); // 설정 변경 이력은 거버넌스 그룹
      loadConfigDrift();
      $("#config-drift-result").scrollIntoView({ block: "center" });
    }
    // 리뷰 카드의 "승인/반려" 링크 — Monitoring 탭 열고 리뷰 게이트로 스크롤
    if (deepView === "review") {
      document.querySelector('.tab[data-tab="monitor"]').click();
      showMonGroup("gov"); // 리뷰 게이트는 거버넌스 그룹
      loadReviews();
      $(".review-gate-card").scrollIntoView({ block: "center" });
    }
  } else if (first) {
    selectInstance(list[0], first);
  }
  // 헬스는 카드 렌더 후 비동기로 채운다 — 죽은 인스턴스가 목록 로딩을 막지 않게.
  // 복제 역할 배지(레퍼런스의 Primary/Secondary 인라인 표기)도 같은 방식 — 역할이 확인되는
  // 기종·구성에서만 배지가 붙고, 미구성·미지원은 조용히 비워둔다(N회 조회지만 데모 규모라 감수).
  list.forEach(async (i) => {
    try {
      const h = await api(`/api/instances/${i.id}/health`);
      $(`#health-${i.id}`).classList.add(h.up ? "up" : "down");
      $(`#healthms-${i.id}`).textContent = h.up ? `${h.pingMillis}ms · ${h.version ?? ""}` : h.message;
    } catch { $(`#health-${i.id}`).classList.add("down"); }
    try {
      const r = await api(`/api/instances/${i.id}/replication`);
      if (r && r.role && r.role !== "UNSUPPORTED" && r.role !== "NONE") {
        $(`#role-${i.id}`).textContent = r.role;
        $(`#role-${i.id}`).classList.add(/PRIMARY|SOURCE|MASTER/i.test(r.role) ? "role-primary" : "role-replica");
      }
    } catch { /* 역할 미확인 — 배지 생략 */ }
  });
  initInstanceFilter(list);
}

// 검색·필터 — 카드 data 속성만 보고 표시/숨김(재렌더 없음). 필터 결과 수를 함께 표기.
// 차원: 기종·환경·리전·클러스터·팀 (레퍼런스의 환경/리전/클러스터 선택 대응 — 이기종에 일반화).
function initInstanceFilter(list) {
  const engineSel = $("#inst-engine"), envSel = $("#inst-env"),
        regionSel = $("#inst-region"), clusterSel = $("#inst-cluster"), teamSel = $("#inst-team");
  // 각 셀렉트를 데이터에 있는 distinct 값으로 채운다(빈 값 제외). 미지정 태그가 하나도 없으면 옵션도 안 생긴다.
  const opts = (sel, placeholder, values) => {
    sel.innerHTML = `<option value="">${placeholder}</option>`
      + [...new Set(values.filter(Boolean))].sort().map((v) => `<option>${esc(v)}</option>`).join("");
  };
  opts(engineSel, "기종 전체", list.map((i) => i.type));
  opts(envSel, "환경 전체", list.map((i) => i.environment));
  opts(regionSel, "리전 전체", list.map((i) => i.region));
  opts(clusterSel, "클러스터 전체", list.map((i) => i.cluster));
  opts(teamSel, "팀 전체", list.map((i) => i.teamLabel));
  const apply = () => {
    const q = $("#inst-search").value.trim().toLowerCase();
    const eng = engineSel.value, env = envSel.value, region = regionSel.value,
          cluster = clusterSel.value, team = teamSel.value;
    let shown = 0;
    document.querySelectorAll(".instance-card").forEach((c) => {
      const hit = (!q || c.dataset.name.includes(q) || c.dataset.host.includes(q))
        && (!eng || c.dataset.type === eng)
        && (!env || c.dataset.env === env)
        && (!region || c.dataset.region === region)
        && (!cluster || c.dataset.cluster === cluster)
        && (!team || c.dataset.team === team);
      c.style.display = hit ? "" : "none";
      if (hit) shown++;
    });
    $("#inst-count").textContent = shown === list.length ? "" : `${shown}/${list.length}`;
  };
  $("#inst-search").oninput = apply;
  [engineSel, envSel, regionSel, clusterSel, teamSel].forEach((s) => { s.onchange = apply; });
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

  await Promise.all([loadActivity(), loadMetrics(), loadBackupInfo(), runQuery(), loadSlow(), loadReplication(), loadWaitEvents(), loadSessions(), loadLatencyPercentiles(), loadSloReport(), loadPartitions(), loadAdvisors(), loadFinOps(), loadAnomalies(), loadPlanChanges(), loadDeadlocks(), loadReviews()]);
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
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다.</div>';
    return;
  }
  // 이미 서버가 나쁜 순으로 정렬해 내려준다 — 죽은 것·백업 없는 것이 위로 온다
  const rows = report.instances.map((s) => {
    // 주요 감점 사유: 감점 있는 신호만, 큰 순(서버가 이미 정렬). 없으면 정상 표기.
    const penalized = s.contributions.filter((c) => c.state === "PENALIZED");
    const reasons = penalized.length
      ? penalized.map((c) => `${SCORE_SIGNAL_LABEL[c.signal] ?? c.signal} −${fmtNum(c.penalty, 0)}`).join(" · ")
      : '<span class="muted">감점 없음</span>';
    // 행 클릭 시 펼칠 신호별 기여 분해(투명성) — 데이터 부족·수집 실패도 그대로 노출
    const detail = s.contributions.map((c) => `
      <div class="score-contrib score-state-${esc(c.state)}">
        <span class="score-contrib-signal">${esc(SCORE_SIGNAL_LABEL[c.signal] ?? c.signal)}</span>
        <span class="score-contrib-state score-state-badge-${esc(c.state)}">${esc(SCORE_STATE_LABEL[c.state] ?? c.state)}</span>
        <span class="score-contrib-penalty">${c.penalty > 0 ? `−${fmtNum(c.penalty, 0)}` : ""}</span>
        <span class="score-contrib-summary">${esc(c.summary)}</span>
      </div>`).join("");
    return `
      <tbody class="score-group" data-id="${s.instanceId}">
        <tr class="score-row score-grade-${esc(s.grade)}">
          <td><span class="type-badge type-${esc(s.type)}">${esc(s.type)}</span> ${esc(s.instanceName)}
            ${s.down ? '<span class="score-down">DOWN</span>' : ""}
            ${s.partial ? '<span class="score-partial-dot" title="일부 신호가 데이터 부족·수집 실패">부분</span>' : ""}</td>
          <td class="num score-num">${s.score}<span class="score-outof">/100</span></td>
          <td><span class="grade-badge grade-${esc(s.grade)}">${esc(s.grade)}</span></td>
          <td class="score-reasons">${reasons}</td>
        </tr>
        <tr class="score-detail-row" hidden><td colspan="4"><div class="score-detail">${detail}</div></td></tr>
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
    box.innerHTML = '<div class="muted">등록된 인스턴스가 없습니다.</div>';
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
    return `
      <tr class="fresh-row fresh-row-${esc(f.status)}">
        <td><span class="type-badge type-${esc(f.type)}">${esc(f.type)}</span> ${esc(f.instanceName)}</td>
        <td><span class="fresh-badge fresh-${esc(f.status)}">${esc(FRESHNESS_LABEL[f.status] ?? f.status)}</span></td>
        <td>${last}</td>
        <td class="num">${elapsed}</td>
        <td>${verify}</td>
        <td>${remote}</td>
      </tr>`;
  }).join("");
  box.innerHTML = `
    <div class="table-scroll">
      <table class="qtable freshness-table">
        <thead><tr><th>인스턴스</th><th>신선도</th><th>마지막 백업</th><th>경과</th><th>복원 검증</th><th>원격 보관</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
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
    empty.hidden = false;
    empty.textContent = note ?? "이 구간에 수집된 시계열이 없습니다";
    return;
  }
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
    $("#chart-empty").hidden = false;
    $("#chart-empty").textContent = state.chartMetric === "cpu"
      ? "CPU 시계열이 없습니다 (node_exporter/Prometheus 미수집)" : "이 구간에 수집된 스냅샷이 없습니다";
    return;
  }
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
  const stats = await api(`/api/instances/${state.instance.id}/query-stats?limit=20`);
  const table = $("#top-table");
  // Call/sec는 스냅샷 차분이라 이력 없으면 null → "—". Latency/Row Examined는 누적÷호출수(평균).
  // Plan 컬럼은 값이 있는 기종(MongoDB — profiler가 계획 요약을 저장)에서만 그린다.
  const hasPlan = stats.some((q) => q.plan);
  table.querySelector("thead").innerHTML = `
    <tr><th>Load</th><th>Query</th><th class="num">Call/sec</th>
        <th class="num">Latency(ms)</th><th class="num">Row Examined (Avg)</th>${hasPlan ? "<th>Plan</th>" : ""}</tr>`;
  table.querySelector("tbody").innerHTML = stats.map((q, idx) => `
    <tr data-idx="${idx}">
      <td class="num">${fmtNum(q.loadPct)}%</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
      <td class="num">${q.callsPerSec == null ? '<span class="muted">—</span>' : fmtNum(q.callsPerSec)}</td>
      <td class="num">${fmtNum(q.avgLatencyMs)}</td>
      <td class="num">${fmtNum(q.rowsExaminedAvg, 0)}</td>
      ${hasPlan ? `<td>${q.plan ? `<span class="plan-badge ${/COLLSCAN/i.test(q.plan) ? "plan-bad" : "plan-ok"}">${esc(q.plan)}</span>` : '<span class="muted">—</span>'}</td>` : ""}
    </tr>`).join("");
  bindRowClicks(stats.map((q) => ({ queryId: q.queryId, queryText: q.queryText })));
}

// ---------- Top Query: 비교 조회 (증감 + NEW) ----------
async function runCompare() {
  const p = (id) => $(id).value;
  if (!p("#base-from") || !p("#base-to") || !p("#target-from") || !p("#target-to")) return;
  closeDetail();
  const qs = `baseFrom=${toApiTime(p("#base-from"))}&baseTo=${toApiTime(p("#base-to"))}&targetFrom=${toApiTime(p("#target-from"))}&targetTo=${toApiTime(p("#target-to"))}`;
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
    <tr><th class="num">Load</th><th>Query</th><th class="num">QPS</th><th class="num">Latency(ms)</th><th class="num">Rows/call</th></tr>`;
  table.querySelector("tbody").innerHTML = rows.map((q, idx) => `
    <tr data-idx="${idx}" class="${q.newQuery ? "new-query" : ""}">
      <td>${deltaCell(baseLoad(q), targetLoad(q), loadPctChange(baseLoad(q), targetLoad(q)))}</td>
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
  host.firstElementChild.appendChild(detail);
  tr.after(host);
}

function openDetail(query, tr) {
  state.currentQuery = query;
  $("#query-detail").hidden = false;
  if (tr) placeDetailUnder(tr);
  $("#detail-qid").textContent = `SQL ID: ${query.queryId}`;
  const formatted = formatSql(query.queryText);
  $("#detail-sql").value = formatted;
  $("#detail-sql").rows = Math.min(24, Math.max(5, formatted.split("\n").length + 1)); // 포매팅 줄 수에 맞춰 높이 자동

  $("#plan-section").hidden = true;
  $("#schema-section").hidden = true;
  $("#schema-result").innerHTML = "";
  $("#ai-section").hidden = true;
  $("#advisor-section").hidden = true;
  $("#advisor-columns").value = "";
  $("#advisor-result").innerHTML = "";
  $("#deep-section").hidden = true;
  $("#deep-result").innerHTML = "";
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
  const detail = $("#query-detail");
  detail.hidden = true;
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
    try {
      data = await api(`/api/instances/${state.instance.id}/explain`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql }),
      });
    } catch (e) {
      $("#detail-plan").textContent = `실행 실패: ${e.message}`;
      $("#detail-findings").innerHTML = "";
      return;
    }
    $("#detail-plan").textContent = prettyPlan(data.plan);
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("") ||
      '<div class="muted">규칙 기반 지적 없음 — 비효율 신호가 발견되지 않았습니다.</div>';
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
  } finally { btn.classList.remove("loading"); }
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
  } finally { btn.classList.remove("loading"); }
}

function renderReferencedSchema(data) {
  const tables = data.tables ?? [];
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
    const cols = (t.columns ?? []).map((c) => `${esc(c.name)} <span class="muted">${esc(c.type)}${c.nullable ? "?" : ""}</span>`).join(", ");
    html += `<div class="finding-item schema-table"><b>${esc(t.name)}</b>${rows}
      <button class="btn btn-small td-toggle" data-table="${esc(t.name)}">상세 보기</button>
      <div class="schema-idx">idx: ${idx}</div>
      <div class="schema-cols">cols: ${cols}</div>
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

// 테이블 상세 정보 — CREATE TABLE·기본 통계·인덱스 카디널리티를 아코디언으로 펼친다.
async function toggleTableDetail(btn) {
  const box = btn.parentElement.querySelector(".td-detail");
  if (!box.hidden) { box.hidden = true; btn.textContent = "상세 보기"; return; }
  box.hidden = false; btn.textContent = "접기";
  if (box.dataset.loaded) return;
  box.innerHTML = '<div class="muted">테이블 상세 조회 중...</div>';
  try {
    const d = await api(`/api/instances/${state.instance.id}/table-detail`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ table: btn.dataset.table }),
    });
    box.innerHTML = renderTableDetail(d);
    box.dataset.loaded = "1";
  } catch (e) {
    box.innerHTML = `<div class="finding-item">상세 조회 실패: ${esc(e.message)}</div>`;
  }
}

function renderTableDetail(d) {
  // 음수(-1)는 미확보를 뜻한다 — 크기 통계는 fmtBytes(null이 아닌 음수) 대신 "—"로 표기
  const bytesOrDash = (v) => v < 0 ? "—" : fmtBytes(v);
  const src = { NATIVE: "", RECONSTRUCTED: '<span class="td-badge">카탈로그 재구성</span>', UNSUPPORTED: '<span class="td-badge">미지원</span>' };
  let html = "";
  // 스키마 정보 (DDL)
  if (d.ddl) {
    html += `<div class="td-block"><div class="td-h">스키마 정보 ${src[d.ddlSource] ?? ""}</div><pre class="codeblock td-ddl">${esc(d.ddl)}</pre></div>`;
  }
  // 기본 통계
  const stat = (k, v) => `<div class="td-stat"><span class="muted">${k}</span><span>${v}</span></div>`;
  html += `<div class="td-block"><div class="td-h">기본 통계</div>
    ${d.engine ? stat("엔진", esc(d.engine)) : ""}
    ${stat("행 수", d.rowCount < 0 ? "—" : d.rowCount.toLocaleString())}
    ${stat("데이터 크기", bytesOrDash(d.dataBytes))}
    ${stat("인덱스 크기", bytesOrDash(d.indexBytes))}
    ${stat("평균 행 길이", bytesOrDash(d.avgRowBytes))}
    ${d.createdAt ? stat("생성 시각", esc(d.createdAt)) : ""}</div>`;
  // 인덱스 정보
  const idxs = d.indexes ?? [];
  if (idxs.length) {
    html += '<div class="td-block"><div class="td-h">인덱스 정보</div>';
    for (const i of idxs) {
      html += `<div class="td-idx-card"><b>${esc(i.name)}</b>${i.unique ? ' <span class="idx-u">UNIQUE</span>' : ""}
        <div class="muted">컬럼: ${esc((i.columns ?? []).join(", "))}</div>
        <div class="muted">타입: ${esc(i.type ?? "—")}</div>
        <div class="muted">카디널리티: ${i.cardinality != null ? Number(i.cardinality).toLocaleString() : "—"}</div></div>`;
    }
    html += "</div>";
  }
  if (d.note) html += `<div class="muted td-note">${esc(d.note)}</div>`;
  return html;
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
    $("#detail-plan").textContent = prettyPlan(data.plan);
    $("#detail-findings").innerHTML = (data.findings ?? []).map((f) =>
      `<div class="finding-item">${esc(f)}</div>`).join("");
    $("#detail-ai").textContent = stripEmoji(data.aiAnalysis) ||
      "AI 분석 비활성화 상태입니다 (ANTHROPIC_API_KEY도 claude CLI도 없음) — 규칙 기반 지적까지만 표시합니다.";
    state.lastPlan = data.plan;
    state.lastFindings = data.findings ?? [];
    state.lastAi = data.aiAnalysis ?? null;
  } finally { btn.classList.remove("loading"); }
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
      html += `<div class="finding-item">제안 인덱스: <code>${esc(data.suggestedIndex)}</code></div>`;
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
  } finally { btn.classList.remove("loading"); }
}

// 심층 원인 진단 (D9) — 실제 실행 계획으로 카디널리티 괴리·근본원인을 짚는다.
// explain(추정)과 달리 쿼리를 실제 실행하므로 ADMIN 전용(서버가 인가). 파라미터 자리는 실제 값이어야 한다.
async function runDeepDiagnose() {
  const sql = $("#detail-sql").value.trim();
  if (!sql) return;
  const btn = $("#btn-deep");
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
    html += `<h3>실제 실행 계획</h3><pre class="codeblock">${esc(data.plan)}</pre>`;
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
  const rows = await api(`/api/instances/${state.instance.id}/slow-queries?limit=20`);
  const table = $("#slow-table");
  // 기종별로 확보 가능한 필드가 달라 미확보는 "—"로 표기(MySQL: User@host·Lock·Rows_sent, Mongo: Plan)
  table.querySelector("thead").innerHTML = `
    <tr><th>Captured <span class="muted" title="브라우저 시간대로 변환 표시 — 원문(UTC)은 툴팁">(로컬)</span></th><th>User@host</th><th class="num">Query(ms)</th><th class="num">Lock(ms)</th>
        <th class="num">Rows_sent</th><th class="num">Rows_examined</th><th>Plan</th><th>Query</th></tr>`;
  const dash = (v) => (v == null || v < 0) ? '<span class="muted">—</span>' : null;
  table.querySelector("tbody").innerHTML = rows.length ? rows.map((q) => `
    <tr>
      <td class="num">${fmtSlowTime(q.capturedAt)}</td>
      <td>${q.userHost ? esc(q.userHost) : '<span class="muted">—</span>'}</td>
      <td class="num">${fmtNum(q.elapsedMs)}</td>
      <td class="num">${dash(q.lockMs) ?? fmtNum(q.lockMs)}</td>
      <td class="num">${dash(q.rowsSent) ?? fmtNum(q.rowsSent, 0)}</td>
      <td class="num">${fmtNum(q.rowsExamined, 0)}</td>
      <td>${q.planSummary ? `<span class="plan-badge ${/COLLSCAN/i.test(q.planSummary) ? "plan-bad" : "plan-ok"}">${esc(q.planSummary)}</span>` : '<span class="muted">—</span>'}</td>
      <td class="qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</td>
    </tr>`).join("") : '<tr><td colspan="8" class="muted">슬로우 쿼리가 없습니다.</td></tr>';
  // Mongo 보존 창 정직 표기 — system.profile은 순환(capped) 컬렉션이라 오래된 항목이 덮어써진다.
  // 로그 파일 파싱 기반 도구와 보존 범위가 다름을 숨기지 않는다.
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

// MCP 카드 — 도구 목록을 실제 /mcp 엔드포인트(tools/list)에서 받아와 그린다.
// 하드코딩하지 않는 이유: 이 목록이 곧 "MCP가 살아 있다"의 증거가 되기 때문.
async function loadMcpTools() {
  const box = $("#mcp-tools");
  try {
    // api() 래퍼를 쓰지 않는 이유 — /mcp는 세션이 아니라 OAuth/토큰 전용 stateless 체인(91절)이라
    // 콘솔 세션으로는 401이 정상이다. 래퍼의 401 처리(로그인 리다이렉트)를 타면 로그인한 사용자가
    // 페이지 진입마다 로그인으로 튕기는 회귀가 된다(실측) — 여기서는 401을 안내 문구로 삼는다.
    const r = await fetch("/mcp", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }),
    });
    if (r.status === 401) {
      box.textContent = "MCP는 토큰 인증 전용입니다 — 클라이언트에서 OAuth 브라우저 로그인 또는 API 토큰으로 접속하세요";
      return;
    }
    if (!r.ok) throw new Error(`${r.status}`);
    const data = await r.json();
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
          <div class="anomaly-q qtext" title="${esc(q.queryText)}">${esc(q.queryText)}</div>
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
    // ADMIN 아니면 403 — 조용히 생략(플랜 변경 카드 자체는 인증 사용자에게 열려 있다).
    changes.forEach(async (c, idx) => {
      try {
        const r = await api(`/api/instances/${state.instance.id}/config-drift/around?at=${encodeURIComponent(c.changedAt)}&hours=24`);
        if (r.changeCount > 0) {
          $(`#drift-around-${idx}`).innerHTML =
            `<a class="drift-hint" href="/?instance=${state.instance.id}&view=config-drift">± ${r.windowHours}h 내 설정 변경 ${r.changeCount}건 — 원인 후보 확인 ↗</a>`;
        }
      } catch (e) { /* 비ADMIN·미수집 — 생략 */ }
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
        <td class="qtext" title="${esc(r.queryText)}">${esc(r.queryText)}</td>
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
    box.innerHTML = '<div class="schema-same">두 스키마가 동일합니다 — 구조 차이 없음.</div>';
    return;
  }
  const parts = [];
  const line = (cls, mark, text) => `<div class="schema-line ${cls}">${mark} ${text}</div>`;
  const tableMeta = (t) => `<span class="muted">(${t.columns.length} cols · ${t.indexes.length} idx)</span>`;

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
      ? '<div class="schema-warning">파라미터 드리프트는 ADMIN 역할만 볼 수 있습니다.</div>'
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
      ? '<div class="schema-warning">설정 변경 이력은 ADMIN 역할만 볼 수 있습니다.</div>'
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
  const isAdmin = state.role === "ADMIN";
  box.innerHTML = rows.map((r) => {
    const badge = r.status === "PENDING" ? '<span class="rv-pending">대기</span>'
      : r.status === "APPROVED" ? '<span class="rv-approved">승인</span>'
      : '<span class="rv-rejected">반려</span>';
    const findings = (r.findings || []).map((f) => `<li>${esc(f)}</li>`).join("");
    const ai = r.aiOpinion ? `<div class="rv-ai"><b>AI 1차 소견:</b> ${esc(r.aiOpinion)}</div>` : "";
    const limited = r.parseLimited ? '<div class="rv-limited">다중 문장·복잡 구문 — 규칙 판정이 불완전할 수 있습니다(사람이 전체 확인).</div>' : "";
    const decided = r.status !== "PENDING"
      ? `<div class="rv-decided muted">${esc(r.decidedBy || "")} · ${esc((r.decidedAt || "").replace("T", " ").slice(0, 19))}${r.decisionComment ? " · " + esc(r.decisionComment) : ""}</div>` : "";
    const actions = (r.status === "PENDING" && isAdmin)
      ? `<div class="rv-actions">
           <button class="btn btn-small btn-primary" onclick="decideReview(${r.id}, true)">승인</button>
           <button class="btn btn-small btn-danger" onclick="decideReview(${r.id}, false)">반려</button>
         </div>`
      : (r.status === "PENDING" ? '<div class="hint">승인/반려는 ADMIN만 가능합니다.</div>' : "");
    return `<div class="rv-item">
      <div class="rv-head">#${r.id} ${badge} <span class="muted">${esc(r.requester)} · rules v${r.rulesVersion}</span></div>
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
    alert(e.message.startsWith("403") ? "승인/반려는 ADMIN만 가능합니다." : `처리 실패: ${e.message}`);
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
      out.push(`<table class="qtable incident-table"><thead><tr>${head.map((h) => `<th>${inline(h)}</th>`).join("")}</tr></thead><tbody>${body}</tbody></table>`);
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
  box.className = "incident-result muted"; box.innerHTML = '<div class="muted">리포트 조립 중... (AI 요약 포함 시 시간이 걸립니다)</div>';
  try {
    const r = await api(`/api/instances/${state.instance.id}/incident-report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&publish=true`, { method: "POST" });
    lastIncidentMarkdown = r.markdown;
    box.className = "incident-result";
    box.innerHTML = mdToHtml(r.markdown);
    $("#btn-incident-dl").hidden = false;
  } catch (e) {
    box.className = "incident-result schema-warning";
    box.textContent = e.message.startsWith("403") ? "인시던트 리포트는 ADMIN 역할만 생성할 수 있습니다." : `생성 실패: ${e.message}`;
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
    box.textContent = e.message.startsWith("403") ? "월간 리포트는 ADMIN 역할만 생성할 수 있습니다." : `생성 실패: ${e.message}`;
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

// ---------- 자연어 근본원인 진단 (D3) ----------
async function runDiagnose() {
  const box = $("#diagnose-result");
  if (!state.instance) { box.className = "diagnose-result schema-warning"; box.textContent = "인스턴스를 먼저 선택하세요."; return; }
  const question = $("#diagnose-question").value.trim();
  if (!question) { box.className = "diagnose-result schema-warning"; box.textContent = "질문을 입력하세요."; return; }

  box.className = "diagnose-result muted";
  box.textContent = "AI가 도구를 연쇄 호출하며 진단 중... (수 초~수십 초 걸릴 수 있습니다)";
  let d;
  try {
    d = await api(`/api/instances/${state.instance.id}/diagnose`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question }),
    });
  } catch (e) {
    box.className = "diagnose-result schema-warning";
    box.textContent = `진단 실패: ${e.message}`;
    return;
  }

  box.className = "diagnose-result";
  if (!d.aiEnabled) {
    box.innerHTML = `<div class="diagnose-note muted">${esc(d.note || "AI 진단 비활성")}</div>`;
    return;
  }

  // 사용한 도구(투명성) — 어떤 도구를 왜 불렀나, 거부된 요청도 표시
  const calls = (d.toolCalls || []).map((c, i) => {
    const badge = c.rejected
      ? `<span class="sev-badge sev-CRITICAL">거부</span>`
      : `<span class="src-badge src-native">${i + 1}</span>`;
    return `
      <div class="diagnose-step">
        <div class="diagnose-step-head">${badge} <code>${esc(c.tool)}</code>
          <span class="muted diagnose-step-args">${esc(c.arguments || "")}</span></div>
        <div class="diagnose-step-reason">${esc(c.reason || "")}</div>
      </div>`;
  }).join("");

  const conf = esc(d.confidence || "");
  box.innerHTML = `
    <div class="diagnose-answer">
      <div class="diagnose-answer-head">
        <strong>근본원인</strong>
        <span class="src-badge conf-${conf}">확신도 ${conf}</span>
        <span class="muted">${esc(d.backend || "")} · 사용 도구 ${d.toolCallCount}개</span>
      </div>
      ${d.rootCause ? `<div class="diagnose-rootcause">${esc(stripEmoji(d.rootCause))}</div>` : ""}
      <div class="diagnose-text">${esc(stripEmoji(d.answer) || "(답변 없음)")}</div>
    </div>
    <div class="diagnose-steps">
      <div class="diagnose-steps-head muted">AI가 부른 도구 (근거·투명성)</div>
      ${calls || '<div class="muted">호출한 도구 없음</div>'}
    </div>
    ${d.note ? `<div class="diagnose-note muted">${esc(d.note)}</div>` : ""}`;
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
  loadHealthScore();     // 함대 전체 통합 헬스 스코어 (D8) — 나쁜 순 정렬, 대시보드 상단 상시 뷰
  loadBackupFreshness(); // 함대 전체 백업 신선도 (D7) — 인스턴스 선택과 무관한 상시 뷰
  setupTabs();
  setupMonitorNav();
  setupPresets();
  setupChartDrag();
  setupCopyButtons();
  loadMcpTools();
  $("#btn-query").addEventListener("click", runQuery);
  $("#btn-compare").addEventListener("click", runCompare);
  $("#btn-explain").addEventListener("click", runExplain);
  $("#btn-schema").addEventListener("click", runReferencedSchema);
  $("#btn-ai").addEventListener("click", runAiAnalysis);
  // "인덱스 제안" 버튼은 섹션을 펼치고, 섹션 안의 "시뮬레이션" 버튼이 실제 호출한다(후보 컬럼 입력이 필요해서)
  $("#btn-advisor").addEventListener("click", () => { $("#advisor-section").hidden = false; $("#advisor-columns").focus(); });
  $("#btn-advisor-run").addEventListener("click", runIndexAdvisor);
  $("#btn-deep").addEventListener("click", runDeepDiagnose);
  $("#btn-inquiry").addEventListener("click", runInquiry);
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
  $("#btn-diagnose").addEventListener("click", runDiagnose);
  $("#diagnose-question").addEventListener("keydown", (e) => { if (e.key === "Enter") runDiagnose(); });
  $("#audit-search-btn").addEventListener("click", loadAudit);
  $("#audit-reset-btn").addEventListener("click", () => {
    ["audit-principal", "audit-action", "audit-outcome"].forEach((id) => { $(`#${id}`).value = ""; });
    loadAudit();
  });
});
