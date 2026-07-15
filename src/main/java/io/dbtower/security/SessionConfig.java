package io.dbtower.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/**
 * 공유 세션(Phase 3) — 세션을 메타 DB(SPRING_SESSION, V15)에 저장한다. 재시작·다중 노드에서
 * 로그인이 살아남는다(ShedLock과 같은 "새 인프라 없이" 논리 — Redis는 규모가 커지면 승급).
 *
 * 명시 @EnableJdbcHttpSession로 켠다 — Boot 자동구성(store-type)만으로는 인메모리로 조용히
 * 폴백해 재시작 생존이 깨지는 것을 실측했다(재시작 후 401). 스키마 단일 권위는 Flyway(V15)라
 * 운영에선 자동 생성을 끄고(테이블은 마이그레이션이 만든다), 테스트(H2)는 spring-session의 H2
 * 스키마 스크립트를 sql.init으로 주입한다(test/resources/application.yml).
 */
@Configuration
@EnableJdbcHttpSession
public class SessionConfig {
}
