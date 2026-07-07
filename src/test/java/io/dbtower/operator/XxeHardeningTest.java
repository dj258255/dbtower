package io.dbtower.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-1: XML 파서 3곳(parseDeadlockXml·parseRingBufferDeadlocks·PlanShapes.fromMssqlXml)의 XXE 방어를
 * 못 박는다. DOCTYPE 자체를 거부(disallow-doctype-decl)하므로 외부 엔티티가 선언된 XML은 파싱 단계에서
 * 실패하고, 그 결과 외부/로컬 파일을 <b>절대 fetch하지 않는다</b>. 방어의 증거로, 로컬 시크릿 파일을
 * 가리키는 SYSTEM 엔티티를 넣어도 결과에 그 파일 내용이 새지 않음을 확인한다.
 *
 * <p>정상 XML은 그대로 파싱돼 기존 동작이 유지되는지도 함께 고정한다(방어가 정상 경로를 깨지 않음).
 */
class XxeHardeningTest {

    private static final String SECRET = "TOP-SECRET-XXE-CANARY-9f3a";

    /** file:// SYSTEM 엔티티로 로컬 파일을 읽으려는 고전적 XXE 페이로드를 만든다. */
    private String fileEntityDoctype(Path secretFile, String rootTag) {
        return "<!DOCTYPE " + rootTag + " [<!ENTITY xxe SYSTEM \""
                + secretFile.toUri() + "\">]>";
    }

    @Test
    void 데드락_파서는_DOCTYPE_외부엔티티를_거부하고_null을_준다(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, SECRET);

        String payload = fileEntityDoctype(secret, "event")
                + """
                <event name="xml_deadlock_report" timestamp="2026-07-06T00:00:00.000Z">
                  <deadlock>
                    <victim-list><victimProcess id="p1"/></victim-list>
                    <process-list>
                      <process id="p1" spid="1"><inputbuf>&xxe;</inputbuf></process>
                    </process-list>
                    <resource-list/>
                  </deadlock>
                </event>""";

        // DOCTYPE 거부 → 파싱 실패 → null(호출부가 스킵). 시크릿 파일을 읽지 않았다.
        assertThat(MsSqlOperator.parseDeadlockXml(payload)).isNull();
    }

    @Test
    void 링버퍼_파서는_DOCTYPE_외부엔티티를_거부하고_빈_목록을_준다(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, SECRET);

        String payload = fileEntityDoctype(secret, "RingBufferTarget")
                + """
                <RingBufferTarget>
                  <event name="xml_deadlock_report" timestamp="2026-07-06T00:00:00.000Z">
                    <deadlock>
                      <victim-list><victimProcess id="p1"/></victim-list>
                      <process-list>
                        <process id="p1" spid="1"><inputbuf>&xxe;</inputbuf></process>
                      </process-list>
                      <resource-list/>
                    </deadlock>
                  </event>
                </RingBufferTarget>""";

        // DOCTYPE 거부 → 파싱 실패 → 빈 목록(파일 타깃 결과만으로 진행).
        assertThat(MsSqlOperator.parseRingBufferDeadlocks(payload)).isEmpty();
    }

    @Test
    void 플랜_shape_파서는_DOCTYPE_외부엔티티를_읽지_않는다(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, SECRET);

        String payload = fileEntityDoctype(secret, "ShowPlanXML")
                + """
                <ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>
                <RelOp PhysicalOp="Index Seek">
                  <IndexScan><Object Index="[&xxe;]"/></IndexScan>
                </RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>""";

        // fromMssqlXml은 파싱 실패 시 텍스트 폴백을 주지만, 어떤 경우에도 시크릿 파일 내용이 섞이면 안 된다.
        String shape = PlanShapes.fromMssqlXml(payload);
        assertThat(shape).doesNotContain(SECRET);
    }

    @Test
    void 정상_데드락_XML은_그대로_파싱된다() {
        String report = """
                <event name="xml_deadlock_report" timestamp="2026-07-06T12:00:00.000Z">
                  <deadlock>
                    <victim-list><victimProcess id="p1"/></victim-list>
                    <process-list>
                      <process id="p1" spid="55"><inputbuf>UPDATE t SET a=1</inputbuf></process>
                    </process-list>
                    <resource-list>
                      <keylock objectname="db.dbo.t" indexname="PK_t" mode="X"/>
                    </resource-list>
                  </deadlock>
                </event>""";

        DeadlockEvent ev = MsSqlOperator.parseDeadlockXml(report);
        assertThat(ev).isNotNull();
        assertThat(ev.detectedAt()).isEqualTo("2026-07-06T12:00:00.000Z");
        assertThat(ev.resource()).isEqualTo("db.dbo.t.PK_t");
    }

    @Test
    void 정상_showplan_XML은_그대로_shape로_파싱된다() {
        String seek = """
                <ShowPlanXML><BatchSequence><Batch><Statements><StmtSimple><QueryPlan>
                <RelOp PhysicalOp="Index Seek">
                  <IndexScan><Object Index="[idx_code]" Table="[products]"/></IndexScan>
                </RelOp></QueryPlan></StmtSimple></Statements></Batch></BatchSequence></ShowPlanXML>""";
        assertThat(PlanShapes.fromMssqlXml(seek)).isEqualTo("Index Seek(idx_code)");
    }
}
