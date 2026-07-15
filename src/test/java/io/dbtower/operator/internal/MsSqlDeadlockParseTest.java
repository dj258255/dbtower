package io.dbtower.operator.internal;

import io.dbtower.operator.model.DeadlockEvent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server 데드락 리포트(xml_deadlock_report) 파싱의 순수 로직만 커넥션 없이 못 박는다 (3차 아크 D-1).
 * file target 조회·권한·롤링은 라이브 검증으로 확인하고, 여기서는 event_data XML → DeadlockEvent 변환의
 * 불변식(victim·statements·resource 추출, 방어적 스킵)만 검증한다.
 *
 * <p>샘플 XML은 Microsoft 문서의 표준 xml_deadlock_report 구조(파일 타깃이 감싸는 {@code <event>} 래퍼 포함)를
 * 따르되 값만 합성한 것이다 — 실제 구조(victim-list/process-list/resource-list, inputbuf, objectname/indexname)를 맞췄다.
 */
class MsSqlDeadlockParseTest {

    /** 파일 타깃이 돌려주는 형태: event 래퍼 + deadlock(victim + process 2개 + resource-list 2개 락). */
    private static final String FULL_REPORT = """
            <event name="xml_deadlock_report" package="sqlserver" timestamp="2026-07-06T12:34:56.789Z">
              <data name="xml_report">
                <value>
                  <deadlock>
                    <victim-list>
                      <victimProcess id="process1a2b3c"/>
                    </victim-list>
                    <process-list>
                      <process id="process1a2b3c" spid="55" status="suspended" isolationlevel="read committed">
                        <inputbuf>
                          UPDATE dbo.orders SET status = 'X' WHERE id = 1
                        </inputbuf>
                      </process>
                      <process id="process4d5e6f" spid="56" status="suspended" isolationlevel="read committed">
                        <inputbuf>UPDATE dbo.customers SET name = 'Y' WHERE id = 2</inputbuf>
                      </process>
                    </process-list>
                    <resource-list>
                      <keylock hobtid="72" dbid="5" objectname="testdb.dbo.orders" indexname="PK_orders" mode="X">
                        <owner-list><owner id="process4d5e6f" mode="X"/></owner-list>
                        <waiter-list><waiter id="process1a2b3c" mode="U" requestType="wait"/></waiter-list>
                      </keylock>
                      <keylock hobtid="73" dbid="5" objectname="testdb.dbo.customers" indexname="PK_customers" mode="X">
                        <owner-list><owner id="process1a2b3c" mode="X"/></owner-list>
                        <waiter-list><waiter id="process4d5e6f" mode="U" requestType="wait"/></waiter-list>
                      </keylock>
                    </resource-list>
                  </deadlock>
                </value>
              </data>
            </event>""";

    @Test
    void victim_statements_resource를_바르게_뽑는다() {
        DeadlockEvent ev = MsSqlOperator.parseDeadlockXml(FULL_REPORT);

        assertThat(ev).isNotNull();
        assertThat(ev.source()).isEqualTo("MSSQL system_health XE");
        assertThat(ev.detectedAt()).isEqualTo("2026-07-06T12:34:56.789Z");

        // 각 process의 inputbuf가 statements로(공백 정리됨)
        assertThat(ev.statements())
                .containsExactly(
                        "UPDATE dbo.orders SET status = 'X' WHERE id = 1",
                        "UPDATE dbo.customers SET name = 'Y' WHERE id = 2");

        // victim = victimProcess id와 매칭된 프로세스의 spid + inputbuf 앞부분
        assertThat(ev.victim())
                .startsWith("spid 55 / ")
                .contains("UPDATE dbo.orders");

        // resource = objectname.indexname 요약(세미콜론 결합)
        assertThat(ev.resource())
                .isEqualTo("testdb.dbo.orders.PK_orders; testdb.dbo.customers.PK_customers");
    }

    @Test
    void inputbuf_없는_프로세스는_빈_문자열로_방어한다() {
        String report = """
                <event name="xml_deadlock_report" timestamp="2026-07-06T00:00:00.000Z">
                  <deadlock>
                    <victim-list><victimProcess id="processAAA"/></victim-list>
                    <process-list>
                      <process id="processAAA" spid="70"/>
                      <process id="processBBB" spid="71">
                        <inputbuf>SELECT 1</inputbuf>
                      </process>
                    </process-list>
                    <resource-list>
                      <objectlock objectname="testdb.dbo.t1" mode="X"/>
                    </resource-list>
                  </deadlock>
                </event>""";

        DeadlockEvent ev = MsSqlOperator.parseDeadlockXml(report);

        assertThat(ev).isNotNull();
        // inputbuf 없는 프로세스는 빈 문자열, 있는 프로세스는 텍스트
        assertThat(ev.statements()).containsExactly("", "SELECT 1");
        // victim은 inputbuf가 비어도 spid로 식별된다
        assertThat(ev.victim()).startsWith("spid 70 / ");
        // indexname 없는 락은 objectname만
        assertThat(ev.resource()).isEqualTo("testdb.dbo.t1");
    }

    @Test
    void deadlock_타임스탬프가_있으면_event보다_우선한다() {
        String report = """
                <event name="xml_deadlock_report" timestamp="2000-01-01T00:00:00.000Z">
                  <deadlock timestamp="2026-07-06T09:09:09.000Z">
                    <victim-list><victimProcess id="processX"/></victim-list>
                    <process-list>
                      <process id="processX" spid="80"><inputbuf>DELETE FROM t</inputbuf></process>
                    </process-list>
                    <resource-list/>
                  </deadlock>
                </event>""";

        DeadlockEvent ev = MsSqlOperator.parseDeadlockXml(report);

        assertThat(ev).isNotNull();
        assertThat(ev.detectedAt()).isEqualTo("2026-07-06T09:09:09.000Z");
        // resource-list가 비면 resource는 null
        assertThat(ev.resource()).isNull();
    }

    @Test
    void 긴_inputbuf는_잘라낸다() {
        String longSql = "SELECT " + "a,".repeat(200); // 400자 이상
        String report = """
                <event timestamp="2026-07-06T00:00:00.000Z">
                  <deadlock>
                    <victim-list><victimProcess id="processL"/></victim-list>
                    <process-list>
                      <process id="processL" spid="90"><inputbuf>%s</inputbuf></process>
                    </process-list>
                    <resource-list/>
                  </deadlock>
                </event>""".formatted(longSql);

        DeadlockEvent ev = MsSqlOperator.parseDeadlockXml(report);

        assertThat(ev).isNotNull();
        // DEADLOCK_STMT_MAX(200)자 + 말줄임("...")
        assertThat(ev.statements().get(0)).hasSize(203).endsWith("...");
    }

    @Test
    void 깨진_XML은_예외를_던지지_않고_null을_돌려준다() {
        assertThat(MsSqlOperator.parseDeadlockXml("<deadlock><process-list><process")).isNull();
        assertThat(MsSqlOperator.parseDeadlockXml("not xml at all")).isNull();
    }

    @Test
    void deadlock_요소가_없으면_null이다() {
        // 다른 종류의 XE 이벤트 XML — 우리가 다룰 리포트가 아니다
        String other = """
                <event name="wait_info" timestamp="2026-07-06T00:00:00.000Z">
                  <data name="wait_type"><value>LCK_M_X</value></data>
                </event>""";
        assertThat(MsSqlOperator.parseDeadlockXml(other)).isNull();
    }

    @Test
    void null이나_공백_입력은_null이다() {
        assertThat(MsSqlOperator.parseDeadlockXml(null)).isNull();
        assertThat(MsSqlOperator.parseDeadlockXml("   ")).isNull();
    }
}
