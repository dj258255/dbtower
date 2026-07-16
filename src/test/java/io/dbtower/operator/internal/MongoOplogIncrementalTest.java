package io.dbtower.operator.internal;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mongo oplog ts 증분 (Phase 2 잔여) — 파일명 마커·증분 쿼리·체인 구멍 판정의 계약 고정.
 * $gte로 직전 마지막 엔트리를 일부러 겹쳐 받는다(겹침 = 연속의 증거), oplog 순환으로 마커가
 * 밀려났으면 조용한 전체 재덤프가 아니라 명확한 실패(FULL 재시작 안내)가 계약이다.
 */
class MongoOplogIncrementalTest {

    @Test
    void 파일명_마커를_파싱하고_규약_밖은_무시한다() {
        assertArrayEquals(new long[]{1752624000, 7},
                MongoOperator.parseTsMarker("mongo-oplog-local-mongo-20260716-020000-ts1752624000_7.archive"));
        // 마커 도입 전 산출물 — null(첫 증분은 전체 덤프로 시작)
        assertNull(MongoOperator.parseTsMarker("mongo-oplog-local-mongo-20260715-010000.archive"));
        assertNull(MongoOperator.parseTsMarker("mongo-local-mongo-20260715-010000.archive"));
    }

    @Test
    void 증분_쿼리는_gte로_직전_엔트리를_겹쳐_받는다() {
        String q = MongoOperator.oplogQuery(1752624000, 7);
        assertEquals("{\"ts\":{\"$gte\":{\"$timestamp\":{\"t\":1752624000,\"i\":7}}}}", q);
        // argv 공백 분리와 무관하게 안전해야 한다 — 공백 없는 압축 JSON
        assertFalse(q.contains(" "));
    }

    @Test
    void 가장_오래된_엔트리가_마커보다_새로우면_체인_구멍이다() {
        // oplog 순환 — 마커(1000,1)가 이미 덮어써짐
        assertTrue(MongoOperator.chainBroken(2000, 1, 1000, 1));
        // 같은 초에서 ordinal만 앞서도 구멍
        assertTrue(MongoOperator.chainBroken(1000, 5, 1000, 4));
        // 마커가 아직 oplog 안에 있음 — 겹침 확보 가능
        assertFalse(MongoOperator.chainBroken(1000, 1, 2000, 1));
        assertFalse(MongoOperator.chainBroken(1000, 1, 1000, 1));
    }
}
