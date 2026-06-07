package io.dbtower;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith 모듈 경계 검증 — 아키텍처 주장을 빌드가 강제한다.
 *
 * "기종 분기는 팩토리 한 곳", "플랫폼 코드는 인터페이스만 안다" 같은 문장은
 * 문서에만 있으면 언젠가 무너진다. 이 테스트는 패키지 = 모듈로 보고
 * 모듈 간 순환 의존을 컴파일 후 즉시 실패시킨다.
 *
 * 도입 시점에 실제로 순환 2개를 잡아냈다 (VERIFICATION 20절):
 * registry <-> operator, insight <-> alert.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(DbtowerApplication.class);

    @Test
    void 모듈_경계와_순환_의존을_검증한다() {
        modules.verify();
    }

    @Test
    void 모듈_구조_문서를_생성한다() {
        // build/spring-modulith-docs/ 에 모듈 다이어그램(PlantUML)과 모듈별 캔버스 생성
        new Documenter(modules).writeDocumentation();
    }
}
