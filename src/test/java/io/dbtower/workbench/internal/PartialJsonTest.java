package io.dbtower.workbench.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 흘러오는 도중의 JSON에서 설명·SQL 앞부분만 꺼내는지 — 어느 조각에서 끊겨도 깨진 글자를 보이지 않아야 한다. */
class PartialJsonTest {

    private static final String FULL = "{\"title\": \"sql\", \"sql\": \"SELECT name\\nFROM customers WHERE grade = \\\"VIP\\\"\", "
            + "\"explanation\": \"등급이 VIP인 고객\\u0020이름을 구합니다.\", \"assumptions\": []}";

    @Test
    void 완성된_JSON에서는_값_전체를_꺼낸다() {
        assertThat(PartialJson.stringPrefix(FULL, "sql")).isEqualTo("SELECT name\nFROM customers WHERE grade = \"VIP\"");
        assertThat(PartialJson.stringPrefix(FULL, "explanation")).isEqualTo("등급이 VIP인 고객 이름을 구합니다.");
    }

    @Test
    void 값_자리에_필드명과_같은_글자가_와도_키로_오인하지_않는다() {
        assertThat(PartialJson.stringPrefix("{\"title\": \"sql\"", "sql")).isNull();
    }

    @Test
    void 어느_위치에서_잘려도_앞부분은_완성본의_접두사다() {
        String sql = PartialJson.stringPrefix(FULL, "sql");
        String explanation = PartialJson.stringPrefix(FULL, "explanation");
        for (int cut = 0; cut <= FULL.length(); cut++) {
            String part = FULL.substring(0, cut);
            String s = PartialJson.stringPrefix(part, "sql");
            String e = PartialJson.stringPrefix(part, "explanation");
            if (s != null) {
                assertThat(sql).as("cut=%d", cut).startsWith(s);
            }
            if (e != null) {
                assertThat(explanation).as("cut=%d", cut).startsWith(e);
            }
        }
    }

    @Test
    void 반쯤_온_이스케이프는_글자로_내보내지_않는다() {
        assertThat(PartialJson.stringPrefix("{\"sql\": \"a\\", "sql")).isEqualTo("a");
        assertThat(PartialJson.stringPrefix("{\"sql\": \"a\\u00", "sql")).isEqualTo("a");
    }

    @Test
    void null이거나_아직_따옴표가_안_열렸으면_없다고_답한다() {
        assertThat(PartialJson.stringPrefix("{\"sql\": null", "sql")).isNull();
        assertThat(PartialJson.stringPrefix("{\"sql\": ", "sql")).isNull();
        assertThat(PartialJson.stringPrefix("{\"sq", "sql")).isNull();
    }
}
