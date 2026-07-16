package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 커넥션 온디맨드 (Phase 4) — idleTimeout 하한 가드 계약 고정.
 * idleTimeout이 수집 주기보다 짧으면 활발한 인스턴스도 매 틱 물리 재연결을 하게 돼
 * 풀의 존재 이유가 사라진다 — 수집 주기 + 30초를 하한으로 강제한다.
 */
class ConnectionPoolsIdleTest {

    @Test
    void idleTimeout이_수집주기보다_짧으면_하한으로_끌어올린다() {
        // 설정 10초, 수집 60초 → 90초(60+30)로 강제 — 틱마다 재연결 폭탄 방지
        assertEquals(90_000, ConnectionPools.guardedIdleTimeout(10_000, 60_000));
    }

    @Test
    void idleTimeout이_충분히_길면_설정값을_그대로_쓴다() {
        assertEquals(600_000, ConnectionPools.guardedIdleTimeout(600_000, 60_000));
    }

    @Test
    void 수집주기가_길면_하한도_따라_올라간다() {
        // 수집 10분이면 하한 10분30초 — 주기보다 짧은 유휴 종료는 어떤 설정에서도 불가
        assertEquals(630_000, ConnectionPools.guardedIdleTimeout(600_000, 600_000));
    }
}
