// 워크시트 AI 대화 — 사람 말풍선, AI 답변(설명·제안 SQL·분류), 체크포인트 카드(제목·만든 사람·시각)를 시간순으로.
// TOI류 도구처럼 요청 하나가 커밋 같은 카드로 남고, 카드에서 편집기로 가져오기·미리보기·되돌리기를 한다.

import { esc, localTime } from "./api.js";
import { highlight } from "./editor.js";

const TIER = { READ: ["읽기", "read"], NEEDS_APPROVAL: ["변경 · 승인 필요", "change"], BLOCKED: ["차단", "blocked"] };
const SOURCE = { AI: "AI 제안", RUN: "직접 실행", RESTORE: "되돌림" };
const time = (t) => localTime(t);

// 이 칸이 무엇을 하는 곳인지에 대한 안내 — 입력창 아래에 떠 있던 문장을 대화 맨 위로 옮겼다.
// 대화가 쌓인 뒤에는 입력창 근처의 작은 글씨를 아무도 다시 읽지 않고, 대화가 있든 없든 같은 자리에 있어야
// "무엇이 실행되고 무엇이 기록되는가"가 이 칸의 규칙으로 먼저 읽힌다(문구는 그대로).
const POLICY = "조회는 조회 전용 계정·읽기 전용 트랜잭션으로 즉시 실행되고, 변경은 승인 티켓으로, "
  + "위험한 문장은 차단됩니다. 모든 실행과 대화는 기록됩니다.";

export function renderTimeline(container, items, { currentVersion, pending, onApply, onPreview, onRestore }) {
  const policy = `<div class="wb-chat-policy">${esc(POLICY)}</div>`;
  if (!items.length && !pending) {
    container.innerHTML = policy + `<div class="wb-empty">
      이 워크시트에서 보고 싶은 데이터를 말로 설명하면 AI가 SQL을 제안합니다.<br>
      AI는 실행하지 않습니다. 제안을 편집기로 가져와 직접 실행하세요.<br>
      <b>선택</b>을 켜고 결과 열이나 스키마의 테이블을 누르면 질문에 붙습니다.</div>`;
    return;
  }
  const sqls = [];
  const card = (v) => checkpointCard(v, sqls, currentVersion);
  const message = (m) => {
    if (m.role === "USER") {
      return `<div class="msg user">${esc(m.content)}${m.valuesShared ? '<span class="shared">결과 값을 함께 보냄</span>' : ""}</div>`;
    }
    let sqlBlock = "";
    if (m.sql) {
      const [label, cls] = TIER[m.tier] || [m.tier || "", ""];
      const unknown = m.unknownTables && m.unknownTables.length
        ? `<span class="unknown">스키마에 없는 테이블: ${esc(m.unknownTables.join(", "))}</span>` : "";
      sqlBlock = `<pre class="ai-sql"><code>${highlight(m.sql)}</code></pre>
        <div class="ai-meta"><span class="badge ${cls}">${esc(label)}${m.kind ? " · " + esc(m.kind) : ""}</span>${unknown}</div>`;
    }
    return `<div class="msg ai"><div class="ai-text">${esc(m.content)}</div>${sqlBlock}${m.version ? card(m.version) : ""}</div>`;
  };

  // 이 칸에는 대화만 둔다(#63) — 직접 실행·되돌림 버전 카드가 실행마다 쌓여 대화가 기록 목록처럼 밀렸다(162절에도 같은 지적).
  // 그 버전들은 탭의 "최신 vN"을 누르면 여는 버전 기록에 모은다. AI 답에 붙은 제안 버전은 답과 함께 둔다
  const talk = items.filter((it) => it.type === "MESSAGE");
  const intro = talk.length || pending ? "" : `<div class="wb-empty wb-empty-inline">
    아직 대화가 없습니다 — 아래에 보고 싶은 데이터를 말로 설명하면 AI가 SQL을 제안합니다.<br>
    직접 실행한 버전은 워크시트 탭의 <b>최신 v</b> 표시를 누르면 봅니다.</div>`;
  container.innerHTML = policy + intro
    + talk.map((it) => message(it.message)).join("")
    + (pending ? `<div class="msg user">${esc(pending.question)}</div>${pendingBubble(pending)}` : "");
  container.scrollTop = container.scrollHeight;

  container.onclick = (e) => {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    if (b.dataset.act === "restore") onRestore(Number(b.dataset.version));
    else if (b.dataset.act === "apply") onApply(sqls[Number(b.dataset.i)]);
    else if (b.dataset.act === "run") onPreview(sqls[Number(b.dataset.i)]);
  };
}

function checkpointCard(v, sqls, currentVersion) {
  const i = sqls.push(v.sql) - 1;
  const label = v.title || SOURCE[v.source] || v.source;
  const from = v.restoredFrom ? ` · v${esc(v.restoredFrom)}에서` : "";
  return `<div class="checkpoint ${v.versionNo === currentVersion ? "current" : ""}">
      <span class="check" aria-hidden="true">✓</span>
      <div class="cp-body">
        <div class="cp-title">v${esc(v.versionNo)} · ${esc(label)}</div>
        <div class="cp-meta">${esc(v.principal)} · ${esc(time(v.createdAt))} · ${esc(SOURCE[v.source] || v.source)}${from}</div>
        <div class="cp-actions">
          <button data-act="apply" data-i="${i}">편집기로</button>
          <button data-act="run" data-i="${i}">미리보기</button>
          <button data-act="restore" data-version="${esc(v.versionNo)}">이 버전으로</button>
        </div>
      </div>
    </div>`;
}

/** 워크시트 버전 기록(#63) — AI 제안·직접 실행·되돌림을 모두 최신부터. 탭의 "최신 vN"이 연다 */
export function renderVersionPanel(container, items, { currentVersion, onApply, onPreview, onRestore }) {
  const versions = items
    .map((it) => (it.type === "MESSAGE" ? it.message.version : it.version))
    .filter(Boolean)
    .sort((a, b) => b.versionNo - a.versionNo);
  const sqls = [];
  container.innerHTML = `<div class="wb-version-head">버전 기록 <span class="muted">${versions.length}개</span></div>`
    + (versions.length ? versions.map((v) => checkpointCard(v, sqls, currentVersion)).join("")
      : '<div class="wb-empty">저장된 버전이 없습니다. 실행하거나 AI 제안을 받으면 버전이 생깁니다.</div>');
  container.onclick = (e) => {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    if (b.dataset.act === "restore") onRestore(Number(b.dataset.version));
    else if (b.dataset.act === "apply") onApply(sqls[Number(b.dataset.i)]);
    else if (b.dataset.act === "run") onPreview(sqls[Number(b.dataset.i)]);
  };
}

// 답을 기다리는 말풍선 — 단계 문구, 흘러오는 설명, 흘러오는 SQL. 아직 저장 전이라 분류 배지와 체크포인트는 없다
function pendingBubble(p) {
  const text = p.explanation ? `<div class="ai-text">${esc(p.explanation)}</div>` : "";
  const sql = p.sql ? `<pre class="ai-sql"><code>${highlight(p.sql)}</code></pre>` : "";
  return `<div class="msg ai pending" aria-live="polite" aria-busy="true">
    <div class="pending-stage">${esc(p.stage || "AI가 스키마를 읽고 SQL을 작성하는 중입니다...")}</div>${text}${sql}</div>`;
}

// 조각이 올 때마다 타임라인 전체를 다시 그리면 앞선 카드의 버튼·스크롤이 매번 초기화된다 — 기다리는 말풍선만 바꾼다
export function updatePending(container, pending) {
  const node = container.querySelector(".msg.ai.pending");
  if (!node) return;
  const nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 40;
  node.outerHTML = pendingBubble(pending);
  // 사람이 위로 올려 읽는 중이면 끌어내리지 않는다
  if (nearBottom) container.scrollTop = container.scrollHeight;
}

export function renderChips(container, chips, onRemove) {
  container.innerHTML = chips.map((c, i) =>
    `<span class="chip">${c.type === "table" ? "#" : "열 "}${esc(c.value)}<button data-i="${i}" title="빼기">×</button></span>`).join("");
  container.onclick = (e) => {
    const b = e.target.closest("button[data-i]");
    if (b) onRemove(Number(b.dataset.i));
  };
}
