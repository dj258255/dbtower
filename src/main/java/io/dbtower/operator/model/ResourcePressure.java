package io.dbtower.operator.model;

/**
 * 인스턴스가 지금 얼마나 밀려 있나 — 자원 압박 신호 (162절).
 *
 * <p>사용자가 원한 것은 "헬스 스코어에 CPU도"였다. 그런데 <b>CPU는 이 플랫폼이 인스턴스에 귀속시킬 수 없다</b> —
 * 데모 스택의 CPU는 node_exporter가 주는 호스트 지표라 같은 노드의 DB들이 같은 값을 공유하고,
 * SLO 카드는 이미 "인프라 지표(CPU)가 아니라 레이턴시·가용성"을 선언하고 있다.
 *
 * <p>대신 <b>DB 자신이 아는 것</b>을 읽는다. 5기종 전부 "지금 몇 개가 동시에 일하고 있고, 한도는 얼마인가"를
 * 자기 통계로 답한다(162절 라이브 실측):
 * <ul>
 *   <li>MySQL — {@code Threads_running} / {@code max_connections}</li>
 *   <li>PostgreSQL — {@code pg_stat_activity} active / {@code max_connections}</li>
 *   <li>SQL Server — 실행 중 요청 / 온라인 스케줄러 수(+ runnable 대기열)</li>
 *   <li>Oracle — {@code v$con_sysmetric}의 Average Active Sessions / CPU core 수</li>
 *   <li>MongoDB — WiredTiger 동시성 티켓 사용 / 총 티켓</li>
 * </ul>
 *
 * <p>이름을 "CPU"라고 하지 않는 이유가 여기 있다. 이건 CPU 사용률이 아니라 <b>동시 실행 압박</b>이고,
 * 그 둘을 같은 이름으로 부르면 다시 "못 읽은 것을 읽은 척"하는 것이 된다.
 * 읽을 수 없는 기종·환경은 이 record를 만들지 않고 empty로 둔다(값을 지어내지 않는다).
 *
 * @param running   지금 실제로 일하고 있는 단위 수(스레드·세션·요청·티켓)
 * @param limit     그 단위의 한도. 한도 개념이 없으면 null
 * @param queued    한도를 넘어 줄 서 있는 수. 기종이 세지 못하면 null
 * @param unit      무엇을 센 것인지(스레드·활성 세션·실행 요청·티켓) — 기종마다 세는 대상이 다르다
 * @param source    그 값을 준 지표 이름(Threads_running 등) — 화면이 근거를 그대로 보인다
 */
public record ResourcePressure(long running, Long limit, Long queued, String unit, String source) {

    /** 한도 대비 사용 비율(0~1). 한도를 모르면 null — 비율을 지어내지 않는다. */
    public Double usedRatio() {
        if (limit == null || limit <= 0) {
            return null;
        }
        return Math.min(1.0, (double) running / limit);
    }
}
