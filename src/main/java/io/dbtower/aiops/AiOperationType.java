package io.dbtower.aiops;

/** DBTower가 실행할 수 있는 AI 운영 작업의 종류. */
public enum AiOperationType {
    QUERY_DIAGNOSIS,
    REGRESSION_EXPLANATION,
    BACKUP_RISK_REVIEW,
    SLO_RISK_REVIEW,
    ADVISOR_SUMMARY,
    COST_REVIEW,
    INCIDENT_TRIAGE,
    DB_TEAM_INQUIRY,
    PERIODIC_REPORT
}
