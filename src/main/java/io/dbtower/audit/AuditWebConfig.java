package io.dbtower.audit;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 감사 인프라 배선 — SecurityConfig(인가 정책)와 분리해 audit 모듈 안에 둔다.
 * 감사를 켜고 끄는 일이 보안 정책 파일을 건드리는 일이 되지 않도록.
 */
@Configuration
public class AuditWebConfig implements WebMvcConfigurer {

    private final AuditInterceptor auditInterceptor;

    public AuditWebConfig(AuditInterceptor auditInterceptor) {
        this.auditInterceptor = auditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 기록 대상이 /api/** 뿐이므로 등록 범위도 좁힌다 — 정적 리소스·로그인 페이지까지 훑을 이유가 없다
        registry.addInterceptor(auditInterceptor).addPathPatterns("/api/**");
    }

    /**
     * 인가 거부(AuthorizationDeniedEvent) 발행을 명시적으로 보장한다.
     * authorizeHttpRequests가 기본 발행하는 버전도 있지만, 403 감사 기록이
     * 프레임워크 기본값에 암묵적으로 기대는 구조가 되지 않게 빈으로 못 박는다.
     */
    @Bean
    public AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }
}
