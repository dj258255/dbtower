package io.dbtower.operator.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플랜 변경 감지가 MongoDB에 보내는 조회를 미리 끊는 가드(160절) — queryStats는 queryHash가 없는 연산에
 * "op:ns" 형태의 대체 식별자를 붙인다. 그 식별자로는 재실행할 조회가 없어 계획도 없으므로, 대상 DB에
 * 결과가 0건으로 정해진 find를 보내기 전에 판별한다.
 */
class MongoPlanShapeGuardTest {

    @Test
    void queryHash는_계획을_뜰_수_있는_식별자다() {
        // 라이브 표본에서 실제로 관측된 해시(160절 — 그룹 5개 중 유일하게 계획을 뜰 수 있던 것)
        assertThat(MongoOperator.isQueryHash("FCE9A3F8")).isTrue();
        assertThat(MongoOperator.isQueryHash("a1b2c3d4")).isTrue();
    }

    @Test
    void 대체_식별자는_계획을_뜰_수_없으므로_걸러낸다() {
        // 같은 라이브 표본의 나머지 4개 — queryStats의 $ifNull 폴백이 만든 "op:ns" 모양이다
        assertThat(MongoOperator.isQueryHash("command:sample.change_it")).isFalse();
        assertThat(MongoOperator.isQueryHash("command:sample.customers")).isFalse();
        assertThat(MongoOperator.isQueryHash("insert:sample.orders")).isFalse();
        assertThat(MongoOperator.isQueryHash("command:sample.$cmd")).isFalse();
    }

    @Test
    void 값이_없으면_추측하지_않고_거른다() {
        assertThat(MongoOperator.isQueryHash(null)).isFalse();
        assertThat(MongoOperator.isQueryHash("")).isFalse();
        assertThat(MongoOperator.isQueryHash("   ")).isFalse();
    }
}
