// 워크벤치 공통 — fetch·CSRF·이스케이프. 대시보드(app.js)와 같은 규칙을 모듈로 옮겼다.

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

// 서버 시각은 UTC 기준 LocalDateTime(오프셋 없음)으로 온다 — DbtowerApplication이 JVM 기본 시간대를 UTC로 고정했다.
// 문자열을 그대로 자르면 KST 화면에 9시간 이른 시각이 찍혔다(132절). 대시보드(app.js parseApiTime)와 같은 규칙으로 브라우저 시간대로 바꾼다.
export const localTime = (t, { seconds = false } = {}) => {
  if (!t) return "";
  const s = String(t).replace(/(\.\d{3})\d+/, "$1");
  const d = new Date(/Z$|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`);
  if (Number.isNaN(d.getTime())) return s.replace("T", " ").slice(0, seconds ? 19 : 16);
  const p = (n) => String(n).padStart(2, "0");
  const hm = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  return seconds ? `${hm}:${p(d.getSeconds())}` : hm;
};

export const csrfToken = () => {
  const m = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : "";
};

export class ApiError extends Error {
  constructor(status, body) {
    super((body && body.error) || `HTTP ${status}`);
    this.status = status;
    this.body = body || {};
    // 서버 로그와 대조할 원인 번호. 본문 전체(JSON)는 화면에 내보내지 않고 이 번호만 짧게 붙인다(148절)
    this.errorId = body && body.errorId ? String(body.errorId) : null;
  }
}

/** 화면에 보일 오류 문장 — 원문(JSON·드라이버 영문·스택)은 빼고 errorId 앞 8자만 덧붙인다(148절, B9) */
export const errText = (e) => {
  const msg = e && e.message ? e.message : "요청을 처리하지 못했습니다.";
  return e && e.errorId ? `${msg} (오류 번호 ${String(e.errorId).slice(0, 8)})` : msg;
};

// raw=true면 성공 응답을 Response 그대로 돌려준다(CSV 내려받기용)
export async function request(path, { method = "GET", body, raw = false } = {}) {
  const headers = {};
  if (method !== "GET") headers["X-XSRF-TOKEN"] = csrfToken();
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  // 세션이 끊기면 폼 로그인으로 리다이렉트된 HTML이 온다 — JSON으로 오해하지 않고 로그인 화면으로 보낸다
  if (res.status === 401 || (res.redirected && res.url.includes("/login"))) {
    location.href = "/login.html";
    throw new ApiError(401, { error: "로그인이 필요합니다" });
  }
  if (raw && res.ok) return res;
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = { error: text };
  }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

// 서버가 흘려보내는 SSE를 POST로 받는다 — EventSource는 GET만 되고 본문과 CSRF 헤더를 실을 수 없다.
// 이벤트 하나마다 onEvent(name, data)를 부르고 스트림이 닫히면 돌아온다. 스트림을 열기 전 거절(JSON 오류)은 ApiError로 던진다.
export async function streamEvents(path, { body, onEvent }) {
  const res = await fetch(path, {
    method: "POST",
    // Accept에 text/event-stream을 싣지 않는다 — 스트림을 열기 전 거절은 JSON으로 오는데, 그걸 받을 수 없다고 선언하면 406이 된다
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": csrfToken() },
    body: JSON.stringify(body),
  });
  if (res.status === 401 || (res.redirected && res.url.includes("/login"))) {
    location.href = "/login.html";
    throw new ApiError(401, { error: "로그인이 필요합니다" });
  }
  if (!res.ok || !(res.headers.get("Content-Type") || "").startsWith("text/event-stream")) {
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
    throw new ApiError(res.status, data);
  }
  const reader = res.body.getReader();
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
