package io.dbtower.operator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 데드락 파싱 (3차 아크 D-2) — SHOW ENGINE INNODB STATUS의 "LATEST DETECTED DEADLOCK"
 * 텍스트에서 최근 1건을 뽑는 {@link MySqlOperator#parseLatestDeadlock} 규약을 JDBC 없이 못 박는다.
 * 핵심 정직성: 헤더가 없으면 빈 목록(미발생이 정상 다수), 부분/절단 출력에서도 예외 없이 가능한 필드만.
 * 샘플은 MySQL 8 INNODB STATUS 출력 구조를 따른 합성 텍스트다(구조만 실제와 일치).
 */
class MySqlDeadlockParseTest {

    /** 트랜잭션 2개 + "WE ROLL BACK TRANSACTION (1)"이 있는 완전한 데드락 섹션 샘플. */
    private static final String WITH_DEADLOCK = """
            =====================================
            2024-01-15 10:30:05 0x7f8a1c0b3700 INNODB MONITOR OUTPUT
            =====================================
            Per second averages calculated from the last 24 seconds
            -----------------
            BACKGROUND THREAD
            -----------------
            srv_master_thread loops: 1 srv_active, 0 srv_shutdown
            ------------------------
            LATEST DETECTED DEADLOCK
            ------------------------
            2024-01-15 10:30:00 0x7f8a1c0b3700
            *** (1) TRANSACTION:
            TRANSACTION 12345, ACTIVE 5 sec starting index read
            mysql tables in use 1, locked 1
            LOCK WAIT 2 lock struct(s), heap size 1136, 1 row lock(s)
            MySQL thread id 10, OS thread handle 140235283412736, query id 100 localhost root updating
            UPDATE accounts SET balance = balance - 100 WHERE id = 1
            *** (1) WAITING FOR THIS LOCK TO BE GRANTED:
            RECORD LOCKS space id 5 page no 4 n bits 72 index PRIMARY of table `bank`.`accounts` trx id 12345 lock_mode X locks rec but not gap waiting
            Record lock, heap no 2 PHYSICAL RECORD: n_fields 5; compact format; info bits 0
            *** (2) TRANSACTION:
            TRANSACTION 12346, ACTIVE 3 sec starting index read
            mysql tables in use 1, locked 1
            3 lock struct(s), heap size 1136, 2 row lock(s)
            MySQL thread id 11, OS thread handle 140235283678976, query id 101 localhost root updating
            UPDATE accounts SET balance = balance - 50 WHERE id = 2
            *** (2) HOLDS THE LOCK(S):
            RECORD LOCKS space id 5 page no 4 n bits 72 index PRIMARY of table `bank`.`accounts` trx id 12346 lock_mode X locks rec but not gap
            Record lock, heap no 2 PHYSICAL RECORD: n_fields 5; compact format; info bits 0
            *** (2) WAITING FOR THIS LOCK TO BE GRANTED:
            RECORD LOCKS space id 5 page no 5 n bits 72 index idx_owner of table `bank`.`ledger` trx id 12346 lock_mode X locks rec but not gap waiting
            Record lock, heap no 3 PHYSICAL RECORD: n_fields 5; compact format; info bits 0
            *** WE ROLL BACK TRANSACTION (1)
            ------------
            TRANSACTIONS
            ------------
            Trx id counter 12347
            Purge done for trx's n:o < 12340 undo n:o < 0 state: running
            """;

    /** 데드락이 한 번도 없던 서버 — LATEST DETECTED DEADLOCK 섹션 자체가 없다. */
    private static final String NO_DEADLOCK = """
            =====================================
            2024-01-15 10:30:05 0x7f8a1c0b3700 INNODB MONITOR OUTPUT
            =====================================
            Per second averages calculated from the last 24 seconds
            ------------
            TRANSACTIONS
            ------------
            Trx id counter 12347
            History list length 0
            """;

    @Test
    void 완전한_섹션이면_victim은_1_statements_2개_resource에_index와_table() {
        List<DeadlockEvent> events = MySqlOperator.parseLatestDeadlock(WITH_DEADLOCK);

        assertThat(events).hasSize(1);
        DeadlockEvent e = events.get(0);

        assertThat(e.source()).isEqualTo("MySQL INNODB STATUS");
        assertThat(e.detectedAt()).isEqualTo("2024-01-15 10:30:00");

        // 두 트랜잭션의 SQL이 각각 잡힌다 (thread id 라인 다음 줄).
        assertThat(e.statements()).hasSize(2);
        assertThat(e.statements().get(0)).isEqualTo("UPDATE accounts SET balance = balance - 100 WHERE id = 1");
        assertThat(e.statements().get(1)).isEqualTo("UPDATE accounts SET balance = balance - 50 WHERE id = 2");

        // victim = 롤백당한 (1)번 트랜잭션 + 그 SQL 앞부분.
        assertThat(e.victim()).contains("트랜잭션 (1) 롤백");
        assertThat(e.victim()).contains("UPDATE accounts SET balance = balance - 100");

        // 경합 리소스에 index/table 요약이 담긴다 (WAITING/HOLDS 락 라인에서).
        assertThat(e.resource()).contains("index PRIMARY of table `bank`.`accounts`");
        assertThat(e.resource()).contains("index idx_owner of table `bank`.`ledger`");
    }

    @Test
    void LATEST_DETECTED_DEADLOCK_헤더가_없으면_빈_목록() {
        assertThat(MySqlOperator.parseLatestDeadlock(NO_DEADLOCK)).isEmpty();
    }

    @Test
    void null_입력도_예외없이_빈_목록() {
        assertThat(MySqlOperator.parseLatestDeadlock(null)).isEmpty();
    }

    @Test
    void 부분_출력이면_예외없이_가능한_필드만_채운다() {
        // 헤더와 (1)번 트랜잭션 SQL까지만 있고 나머지(2번·롤백·락 라인)가 잘린 절단 출력.
        String partial = """
                ------------------------
                LATEST DETECTED DEADLOCK
                ------------------------
                2024-01-15 09:00:00 0x7f00
                *** (1) TRANSACTION:
                TRANSACTION 999, ACTIVE 2 sec starting index read
                MySQL thread id 7, OS thread handle 140, query id 55 localhost root updating
                DELETE FROM jobs WHERE id = 42
                """;

        List<DeadlockEvent> events = MySqlOperator.parseLatestDeadlock(partial);
        assertThat(events).hasSize(1);
        DeadlockEvent e = events.get(0);

        assertThat(e.detectedAt()).isEqualTo("2024-01-15 09:00:00");
        assertThat(e.statements()).containsExactly("DELETE FROM jobs WHERE id = 42");
        assertThat(e.victim()).isNull();     // WE ROLL BACK 라인이 없어 victim 미상
        assertThat(e.resource()).isNull();   // 락 라인이 없어 resource 미상
        assertThat(e.source()).isEqualTo("MySQL INNODB STATUS");
    }

    @Test
    void 헤더는_있으나_시각_라인이_없으면_detectedAt은_null() {
        String noTs = """
                ------------------------
                LATEST DETECTED DEADLOCK
                ------------------------
                *** (1) TRANSACTION:
                MySQL thread id 3, OS thread handle 1, query id 9 localhost root updating
                SELECT * FROM t WHERE k = 1 FOR UPDATE
                *** WE ROLL BACK TRANSACTION (1)
                """;

        List<DeadlockEvent> events = MySqlOperator.parseLatestDeadlock(noTs);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detectedAt()).isNull();
        assertThat(events.get(0).victim()).contains("트랜잭션 (1) 롤백");
    }
}
