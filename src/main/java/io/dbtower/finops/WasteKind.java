package io.dbtower.finops;

/**
 * 낭비 후보의 종류 (D6 FinOps) — 웹 콘솔이 종류별로 묶어 보여주는 분류.
 *
 * 전부 "신호"까지다. DBTower는 대상 DB를 스스로 바꾸지 않는다(정체성 가드레일) — 인덱스 자동 삭제도,
 * rightsizing 실행도 하지 않는다. 각 후보는 "여기에 낭비 신호가 있다, 사람이 확인하라"까지만 말한다.
 */
public enum WasteKind {

    /** 사용 통계상 스캔 0회로 남은 인덱스 — 쓰기 비용·저장공간만 물 수 있는 미사용 후보 */
    UNUSED_INDEX("미사용 인덱스 후보"),

    /** 컬럼 구성이 겹치거나 접두로 포함되는 잉여 인덱스 후보(D2 구조 판정 재사용) */
    REDUNDANT_INDEX("중복·잉여 인덱스 후보"),

    /** 크기 상위의 큰 테이블 — 아카이빙·파티셔닝·콜드 스토리지 검토 신호 */
    LARGE_TABLE("큰 테이블"),

    /** 인덱스 크기가 데이터 크기를 넘어서는 과다 인덱싱 신호 */
    OVER_INDEXED("과다 인덱싱 신호"),

    /** 활성 세션이 max_connections를 크게 밑도는 연결 여유(오버프로비저닝 신호) */
    CONNECTION_HEADROOM("연결 여유 신호"),

    /** 버퍼/캐시 설정이 데이터 총량을 크게 웃도는 메모리 여유(오버프로비저닝 신호) */
    MEMORY_HEADROOM("메모리 여유 신호");

    private final String label;

    WasteKind(String label) {
        this.label = label;
    }

    /** 사람이 읽는 이름(웹 콘솔 표시). */
    public String label() {
        return label;
    }
}
