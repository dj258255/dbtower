// 워크벤치 공통 — fetch·CSRF·이스케이프. 대시보드(app.js)와 같은 규칙을 모듈로 옮겼다.

export const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

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
