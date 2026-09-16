package io.dbtower.aiops;

/** 작업이 어디서 시작됐는가 — 자유 문자열로 받으면 집계·필터가 표기 차이로 갈라진다. */
public enum AiOperationTrigger {
    WEB,
    SLACK,
    ALERT,
    SCHEDULE,
    API
}
