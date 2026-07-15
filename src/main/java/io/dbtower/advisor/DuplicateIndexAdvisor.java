package io.dbtower.advisor;

import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.model.IndexSchema;
import io.dbtower.operator.model.SchemaSnapshot;
import io.dbtower.operator.model.TableSchema;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 중복·잉여 인덱스 후보 점검 (Phase D2) — describeSchema()(B7)의 인덱스 구조만으로 판정한다.
 *
 * 두 종류의 후보를 잡는다:
 * - 완전 중복: 같은 테이블에서 컬럼 구성(순서 포함)이 동일한 인덱스 둘 이상.
 * - 접두 잉여: 한 인덱스의 컬럼열이 다른 인덱스의 접두(prefix)라, 넓은 인덱스가 좁은 인덱스를 대개 대신한다.
 *
 * 정직한 한계: 인덱스 "사용 흔적"(실제 쿼리에서 쓰였는지)은 describeSchema로 알 수 없어, 여기 결과는
 * 삭제 대상이 아니라 검토 "후보"다. 유니크 제약·FK 지원·부분/포함 인덱스 차이로 실제로는 필요한 경우가
 * 있으므로 title·recommendation에 "후보"임을 명시한다. describeSchema를 구현하는 5기종 모두 지원한다
 * (MongoDB는 컬렉션 인덱스 기준).
 */
@Component
public class DuplicateIndexAdvisor implements Advisor {

    @Override
    public String id() {
        return "duplicate-index";
    }

    @Override
    public String title() {
        return "중복·잉여 인덱스 후보";
    }

    @Override
    public boolean supports(DbmsType type) {
        return true; // describeSchema는 5기종 모두 구현(스키마리스 Mongo도 인덱스는 있음)
    }

    @Override
    public List<AdvisorFinding> inspect(DatabaseInstance instance, DbmsOperator operator) {
        return evaluate(operator.describeSchema());
    }

    /** 순수 판정 — 스키마 스냅샷의 인덱스 구조만으로 중복·접두 잉여 후보를 찾는다. */
    public List<AdvisorFinding> evaluate(SchemaSnapshot snapshot) {
        List<AdvisorFinding> findings = new ArrayList<>();
        for (TableSchema table : snapshot.tables()) {
            List<IndexSchema> indexes = table.indexes();
            for (int i = 0; i < indexes.size(); i++) {
                for (int j = i + 1; j < indexes.size(); j++) {
                    IndexSchema a = indexes.get(i);
                    IndexSchema b = indexes.get(j);
                    List<String> ca = a.columns();
                    List<String> cb = b.columns();
                    // 컬럼으로 환원되지 않는 인덱스(표현식 등)는 비교에서 제외 — 오탐 방지
                    if (ca.isEmpty() || cb.isEmpty()) {
                        continue;
                    }
                    if (ca.equals(cb)) {
                        findings.add(new AdvisorFinding(Severity.WARNING,
                                "완전 중복 인덱스 후보: " + table.name() + " — " + a.name() + " / " + b.name(),
                                "두 인덱스의 컬럼 구성이 동일하다(" + cols(ca) + "). 쓰기마다 둘 다 갱신돼 저장·쓰기 비용만 늘 수 있다.",
                                "사용 통계로 확인 후, 유니크 제약이 걸리지 않은 한쪽을 삭제 후보로 검토한다."));
                    } else if (isPrefix(ca, cb)) {
                        findings.add(prefixFinding(table.name(), a, b));
                    } else if (isPrefix(cb, ca)) {
                        findings.add(prefixFinding(table.name(), b, a));
                    }
                }
            }
        }
        return findings;
    }

    /** shorter의 컬럼열이 longer의 접두이면 shorter는 longer로 대개 대체 가능한 잉여 후보다. */
    private AdvisorFinding prefixFinding(String tableName, IndexSchema shorter, IndexSchema longer) {
        return new AdvisorFinding(Severity.INFO,
                "접두 잉여 인덱스 후보: " + tableName + " — " + shorter.name() + " ⊂ " + longer.name(),
                shorter.name() + "(" + cols(shorter.columns()) + ")의 컬럼이 "
                        + longer.name() + "(" + cols(longer.columns()) + ")의 접두라, 넓은 인덱스가 대개 대신한다.",
                "선두 컬럼 단독 조회·유니크 제약이 없다면 좁은 인덱스를 삭제 후보로 검토한다.");
    }

    private static boolean isPrefix(List<String> prefix, List<String> full) {
        if (prefix.size() >= full.size()) {
            return false; // 같은 길이는 equals에서 이미 처리, 더 길면 접두가 아니다
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(full.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String cols(List<String> columns) {
        return String.join(", ", columns);
    }
}
