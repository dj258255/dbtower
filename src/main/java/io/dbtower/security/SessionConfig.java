package io.dbtower.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

/**
 * 공유 세션(Phase 3) — 세션을 메타 DB(SPRING_SESSION, V15)에 저장한다. 재시작·다중 노드에서
 * 로그인이 살아남는다(ShedLock과 같은 "새 인프라 없이" 논리 — Redis는 규모가 커지면 승급).
 *
 * 명시 @EnableJdbcHttpSession로 켠다 — application.yml의 session.store-type=jdbc와 함께,
 * 자동구성 조건이 어긋나 인메모리로 조용히 폴백하는 것을 막는다(재시작 생존이 핵심 계약이므로).
 */
@Configuration
@EnableJdbcHttpSession
public class SessionConfig {
}
