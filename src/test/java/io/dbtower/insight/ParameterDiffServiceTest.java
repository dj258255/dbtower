package io.dbtower.insight;

import io.dbtower.operator.DbParameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 파라미터 drift 로직 검증 (B6) — 대상 DB 없이 두 파라미터 목록의 차이를 정확히 잡는지 증명한다.
 * "왜 저 장비만 느리지"의 실제 원인(값 차이, 한쪽에만 있는 파라미터, 기종 혼합)을 모두 재현한다.
 */
class ParameterDiffServiceTest {

    private final ParameterDiffService service = new ParameterDiffService();

    private static DbParameter p(String name, String value) {
        return new DbParameter(name, value, null);
    }

    @Test
    void 값차이와_한쪽에만_있는_항목을_각각_잡는다() {
        // left(베이스라인)
        List<DbParameter> left = List.of(
                p("max_connections", "100"),
                p("work_mem", "4MB"),
                p("shared_buffers", "128MB"),   // 좌우 동일 — drift 아님
                p("log_statement", "none"));    // left에만
        // right(느린 장비): work_mem 작고 max_connections 다르고, autovacuum은 right에만
        List<DbParameter> right = List.of(
                p("max_connections", "200"),
                p("work_mem", "1MB"),
                p("shared_buffers", "128MB"),
                p("autovacuum", "on"));         // right에만

        ParameterDiffService.ParameterDiff diff =
                service.diff("POSTGRESQL", left, "POSTGRESQL", right);

        assertFalse(diff.identical());
        assertNull(diff.warning(), "같은 기종이면 경고 없음");

        // 변경: max_connections, work_mem (이름순)
        assertEquals(List.of("max_connections", "work_mem"),
                diff.changed().stream().map(ParameterDiffService.ParameterDrift::name).toList());
        ParameterDiffService.ParameterDrift wm = diff.changed().stream()
                .filter(d -> d.name().equals("work_mem")).findFirst().orElseThrow();
        assertEquals("4MB", wm.leftValue());
        assertEquals("1MB", wm.rightValue());

        // 한쪽에만
        assertEquals(List.of("log_statement"),
                diff.leftOnly().stream().map(DbParameter::name).toList());
        assertEquals(List.of("autovacuum"),
                diff.rightOnly().stream().map(DbParameter::name).toList());
    }

    @Test
    void 값이_모두_같으면_identical이고_목록이_비어있다() {
        List<DbParameter> params = List.of(p("max_connections", "100"), p("work_mem", "4MB"));

        ParameterDiffService.ParameterDiff diff =
                service.diff("MYSQL", params, "MYSQL", params);

        assertTrue(diff.identical());
        assertTrue(diff.changed().isEmpty());
        assertTrue(diff.leftOnly().isEmpty());
        assertTrue(diff.rightOnly().isEmpty());
    }

    @Test
    void 기종이_다르면_이름체계_차이_경고를_싣는다() {
        List<DbParameter> pg = List.of(p("max_connections", "100"));
        List<DbParameter> ora = List.of(p("processes", "300")); // Oracle은 이름 자체가 다름

        ParameterDiffService.ParameterDiff diff =
                service.diff("POSTGRESQL", pg, "ORACLE", ora);

        assertNotNull(diff.warning());
        assertTrue(diff.warning().contains("기종이 다릅니다"));
        // 이름이 겹치지 않으니 전부 한쪽에만으로 나온다
        assertEquals(1, diff.leftOnly().size());
        assertEquals(1, diff.rightOnly().size());
        assertTrue(diff.changed().isEmpty());
    }
}
