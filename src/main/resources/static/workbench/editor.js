// SQL 편집기 — 의존성 없이 textarea + 하이라이트 오버레이 + 스키마 기반 자동완성.
// 편집은 브라우저 기본 textarea가 맡고(한글 입력·실행 취소·선택이 그대로 동작), 색은 뒤에 겹친 pre가 칠한다.

import { esc } from "./api.js";

const KEYWORDS = [
  "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS",
  "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "USING", "GROUP", "BY", "ORDER",
  "HAVING", "LIMIT", "OFFSET", "FETCH", "FIRST", "ROWS", "ONLY", "TOP", "DISTINCT", "AS", "CASE",
  "WHEN", "THEN", "ELSE", "END", "UNION", "ALL", "INTERSECT", "EXCEPT", "WITH", "ASC", "DESC",
  "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "CAST", "INSERT", "INTO", "VALUES", "UPDATE",
  "SET", "DELETE", "CREATE", "ALTER", "DROP", "TABLE", "INDEX", "VIEW", "EXPLAIN", "ANALYZE", "SHOW",
  "DESCRIBE", "TRUE", "FALSE", "OVER", "PARTITION", "INTERVAL", "NOW", "CURRENT_DATE", "CURRENT_TIMESTAMP",
];
const KW = new Set(KEYWORDS);

// 주석 | 문자열·인용 식별자 | 숫자 | 단어 | 그 밖의 한 글자 — 마지막 대안이 모든 문자를 덮어 빠짐없이 칠한다
const TOKEN = /(--[^\n]*|\/\*[\s\S]*?(?:\*\/|$))|('(?:[^']|'')*'?|"(?:[^"]|"")*"?|`[^`]*`?)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_$]*)|([\s\S])/g;

export function highlight(text) {
  let out = "";
  for (const m of text.matchAll(TOKEN)) {
    const [tok, cmt, str, num, word] = m;
    if (cmt) out += `<span class="tk-cmt">${esc(cmt)}</span>`;
    else if (str) out += `<span class="tk-str">${esc(str)}</span>`;
    else if (num) out += `<span class="tk-num">${esc(num)}</span>`;
    else if (word) out += KW.has(word.toUpperCase()) ? `<span class="tk-kw">${esc(word)}</span>` : esc(word);
    else out += esc(tok);
  }
  // 마지막 줄이 빈 줄이면 pre가 높이를 접어 커서와 어긋난다 — 줄바꿈 하나로 받친다
  return out + "\n";
}

const MIRROR = ["boxSizing", "width", "borderTopWidth", "borderLeftWidth", "paddingTop", "paddingRight", "paddingLeft",
  "fontFamily", "fontSize", "fontWeight", "lineHeight", "letterSpacing", "tabSize", "whiteSpace", "wordWrap"];

// 커서의 화면 좌표 — textarea와 같은 스타일의 숨은 div에 커서 앞 글자를 채워 마지막 span 위치를 잰다
function caretPosition(textarea, pos) {
  const style = getComputedStyle(textarea);
  const div = document.createElement("div");
  for (const p of MIRROR) div.style[p] = style[p];
  Object.assign(div.style, { position: "absolute", visibility: "hidden", top: "0", left: "0", overflow: "hidden" });
  div.textContent = textarea.value.slice(0, pos);
  const marker = document.createElement("span");
  marker.textContent = "​";
  div.appendChild(marker);
  textarea.parentElement.appendChild(div);
  const top = marker.offsetTop - textarea.scrollTop + parseFloat(style.lineHeight || "20");
  const left = marker.offsetLeft - textarea.scrollLeft;
  div.remove();
  return { top, left };
}

export class SqlEditor {
  constructor({ input, highlighter, complete, onRun, onChange }) {
    this.input = input;
    this.highlighter = highlighter;
    this.complete = complete;
    this.onRun = onRun;
    this.onChange = onChange;
    this.tables = new Map();
    this.items = [];
    this.active = 0;
    this.prefixLength = 0;

    input.addEventListener("input", () => {
      this.render();
      this.onChange?.(input.value);
      this.suggest();
    });
    input.addEventListener("scroll", () => {
      const pre = this.highlighter.parentElement;
      pre.scrollTop = input.scrollTop;
      pre.scrollLeft = input.scrollLeft;
    });
    input.addEventListener("keydown", (e) => this.onKey(e));
    input.addEventListener("blur", () => setTimeout(() => this.close(), 150));
    input.addEventListener("click", () => this.close());
    complete.addEventListener("mousedown", (e) => {
      const li = e.target.closest("li[data-i]");
      if (!li) return;
      e.preventDefault();
      this.accept(Number(li.dataset.i));
    });
    this.render();
  }

  get value() {
    return this.input.value;
  }

  set value(text) {
    this.input.value = text ?? "";
    this.render();
  }

  render() {
    this.highlighter.innerHTML = highlight(this.input.value);
  }

  focus() {
    this.input.focus();
  }

  // 선택 영역이 있으면 그 부분만 — 한 탭에 여러 문장을 두고 골라 실행하는 흔한 사용법
  statementToRun() {
    const { selectionStart: s, selectionEnd: e, value } = this.input;
    const selected = value.slice(s, e);
    return selected.trim() ? selected : value;
  }

  insert(text) {
    const { selectionStart: s, selectionEnd: e, value } = this.input;
    this.input.value = value.slice(0, s) + text + value.slice(e);
    const pos = s + text.length;
    this.input.setSelectionRange(pos, pos);
    this.render();
    this.onChange?.(this.input.value);
    this.focus();
  }

  setSchema(schema) {
    this.tables = new Map();
    for (const t of schema?.tables || []) {
      this.tables.set(t.name.toLowerCase(), { name: t.name, columns: t.columns.map((c) => c.name) });
    }
  }

  onKey(e) {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      this.close();
      this.onRun?.();
      return;
    }
    if (!this.complete.hidden) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const step = e.key === "ArrowDown" ? 1 : -1;
        this.active = (this.active + step + this.items.length) % this.items.length;
        this.show();
        return;
      }
      if (e.key === "Enter" || e.key === "Tab") {
        e.preventDefault();
        this.accept(this.active);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        this.close();
        return;
      }
    }
    if (e.key === "Tab") {
      e.preventDefault();
      this.insert("  ");
    }
  }

  suggest() {
    const pos = this.input.selectionStart;
    const before = this.input.value.slice(0, pos);
    const dotted = before.match(/([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)?$/);
    let prefix;
    let candidates;
    if (dotted) {
      prefix = dotted[2] || "";
      const table = this.resolveTable(dotted[1]);
      candidates = table ? table.columns.map((c) => ({ label: c, kind: "열" })) : [];
    } else {
      const word = before.match(/[A-Za-z_][A-Za-z0-9_]*$/);
      if (!word) return this.close();
      prefix = word[0];
      const tables = [...this.tables.values()].map((t) => ({ label: t.name, kind: "테이블" }));
      const afterTableKeyword = /\b(from|join|update|into|table|describe)\s+[A-Za-z0-9_]*$/i.test(before);
      candidates = afterTableKeyword
        ? tables
        : [...this.mentionedColumns(), ...tables, ...KEYWORDS.map((k) => ({ label: k, kind: "키워드" }))];
    }
    const lower = prefix.toLowerCase();
    const seen = new Set();
    this.items = candidates.filter((c) => {
      const l = c.label.toLowerCase();
      if (!l.startsWith(lower) || l === lower || seen.has(l)) return false;
      seen.add(l);
      return true;
    }).slice(0, 12);
    if (!this.items.length) return this.close();
    this.prefixLength = prefix.length;
    this.active = 0;
    this.show();
  }

  // 별칭(FROM orders o)을 실제 테이블로 되돌린다
  resolveTable(nameOrAlias) {
    const key = nameOrAlias.toLowerCase();
    if (this.tables.has(key)) return this.tables.get(key);
    const re = /\b(?:from|join)\s+([A-Za-z_][A-Za-z0-9_.]*)\s+(?:as\s+)?([A-Za-z_][A-Za-z0-9_]*)/gi;
    for (const m of this.input.value.matchAll(re)) {
      if (m[2].toLowerCase() === key) return this.tables.get(m[1].split(".").pop().toLowerCase());
    }
    return null;
  }

  // 문장에 등장한 테이블의 열을 먼저 제안한다 — 전체 스키마의 열을 다 띄우면 후보가 소음이 된다
  mentionedColumns() {
    const out = [];
    const re = /\b(?:from|join|update|into)\s+([A-Za-z_][A-Za-z0-9_.]*)/gi;
    for (const m of this.input.value.matchAll(re)) {
      const table = this.tables.get(m[1].split(".").pop().toLowerCase());
      if (table) table.columns.forEach((c) => out.push({ label: c, kind: `열 · ${table.name}` }));
    }
    return out;
  }

  show() {
    this.complete.innerHTML = this.items.map((item, i) =>
      `<li data-i="${i}" role="option" class="${i === this.active ? "active" : ""}"><span>${esc(item.label)}</span><span>${esc(item.kind)}</span></li>`
    ).join("");
    const { top, left } = caretPosition(this.input, this.input.selectionStart);
    this.complete.style.top = `${Math.max(0, top)}px`;
    this.complete.style.left = `${Math.max(0, Math.min(left, this.input.clientWidth - 220))}px`;
    this.complete.hidden = false;
  }

  accept(i) {
    const item = this.items[i];
    if (!item) return;
    const { selectionStart: s, value } = this.input;
    const start = s - this.prefixLength;
    this.input.value = value.slice(0, start) + item.label + value.slice(s);
    const pos = start + item.label.length;
    this.input.setSelectionRange(pos, pos);
    this.close();
    this.render();
    this.onChange?.(this.input.value);
  }

  close() {
    this.complete.hidden = true;
    this.items = [];
  }
}
