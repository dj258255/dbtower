// 워크시트 AI 대화 — 사람 말풍선, AI 답변(설명·제안 SQL·분류), 체크포인트 카드(제목·만든 사람·시각)를 시간순으로.
// TOI류 도구처럼 요청 하나가 커밋 같은 카드로 남고, 카드에서 편집기로 가져오기·미리보기·되돌리기를 한다.

import { esc } from "./api.js";
import { highlight } from "./editor.js";

const TIER = { READ: ["읽기", "read"], NEEDS_APPROVAL: ["변경 · 승인 필요", "change"], BLOCKED: ["차단", "blocked"] };
const SOURCE = { AI: "AI 제안", RUN: "직접 실행", RESTORE: "되돌림" };
const time = (t) => String(t || "").replace("T", " ").slice(0, 16);

export function renderTimeline(container, items, { currentVersion, pending, onApply, onPreview, onRestore }) {
  if (!items.length && !pending) {
    container.innerHTML = `<div class="wb-empty">
      이 워크시트에서 보고 싶은 데이터를 말로 설명하면 AI가 SQL을 제안합니다.<br>
      AI는 실행하지 않습니다. 제안을 편집기로 가져와 직접 실행하세요.<br>
      <b>선택</b>을 켜고 결과 열이나 스키마의 테이블을 누르면 질문에 붙습니다.</div>`;
    return;
  }
  const sqls = [];
  const card = (v) => {
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
  };
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

  container.innerHTML = items.map((it) => (it.type === "MESSAGE" ? message(it.message) : `<div class="msg ai">${card(it.version)}</div>`)).join("")
    + (pending ? `<div class="msg user">${esc(pending)}</div><div class="msg ai pending">AI가 스키마를 읽고 SQL을 작성하는 중입니다...</div>` : "");
  container.scrollTop = container.scrollHeight;

  container.onclick = (e) => {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    if (b.dataset.act === "restore") onRestore(Number(b.dataset.version));
    else if (b.dataset.act === "apply") onApply(sqls[Number(b.dataset.i)]);
    else if (b.dataset.act === "run") onPreview(sqls[Number(b.dataset.i)]);
  };
}

export function renderChips(container, chips, onRemove) {
  container.innerHTML = chips.map((c, i) =>
    `<span class="chip">${c.type === "table" ? "#" : "열 "}${esc(c.value)}<button data-i="${i}" title="빼기">×</button></span>`).join("");
  container.onclick = (e) => {
    const b = e.target.closest("button[data-i]");
    if (b) onRemove(Number(b.dataset.i));
  };
}
