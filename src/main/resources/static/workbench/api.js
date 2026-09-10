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
  }
}

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
