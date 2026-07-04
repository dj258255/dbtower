package io.dbtower;

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 스케줄러 분산 락 인프라 (Phase A5) — 다중 인스턴스(HA) 안전.
 *
 * 왜 필요한가: 이 플랫폼의 폴러들(스냅샷 수집·회귀 감지·보존 정리·백업)은 단일 프로세스 전제였다.
 * 앱을 2개 이상 띄우면 모든 노드가 같은 주기에 같은 대상 DB를 동시에 수집·백업하고,
 * 회귀 알림은 중복으로 나간다. ShedLock으로 "한 시점에 한 노드만" 실행되게 강제한다.
 *
 * 왜 루트 패키지(io.dbtower)인가: LockProvider는 특정 모듈이 아니라 모든 폴러가 공유하는 인프라다.
 * 어느 한 모듈(insight/alert/backup)에 두면 다른 모듈이 그 모듈을 참조하게 되어 Modulith 순환을 유발한다.
 * 애플리케이션 베이스 패키지(모듈이 아닌 공용 영역)에 두어 순환을 원천 차단한다.
 *
 * 왜 JdbcTemplate 프로바이더인가: 이미 PostgreSQL 메타 DB가 있으므로 락 테이블(V3) 하나만 추가하면 되고,
 * Redis/ZooKeeper 같은 별도 인프라를 도입하지 않는다.
 */
@Configuration
// defaultLockAtMostFor: 각 @SchedulerLock이 lockAtMostFor를 명시하지 않았을 때의 안전망 상한.
// 여기서는 모든 폴러가 값을 명시하므로 실사용되진 않지만, 누락 시 무한 보유를 막는 방어값으로 둔다.
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // usingDbTime: 락 만료 판단 기준 시각을 각 노드의 JVM 시계가 아니라 메타 DB의 now()로 통일한다.
                        // HA에서 노드 간 시계 오차가 있으면 앱 시각 기준은 락을 너무 일찍/늦게 풀 수 있다 —
                        // DB 한 곳을 단일 시간 기준으로 삼아 그 오차를 제거한다(ShedLock 권장).
                        .usingDbTime()
                        .build());
    }
}
