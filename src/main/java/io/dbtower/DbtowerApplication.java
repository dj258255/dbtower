package io.dbtower;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DbtowerApplication {

	public static void main(String[] args) {
		// C-6: JVM 기본 타임존을 UTC로 고정한다. SpringApplication.run 이전에 세워야 이후 생성되는 모든
		// LocalDateTime.now()·Timestamp가 UTC 기준으로 일관된다. 노드마다 서버 TZ가 달라도 스냅샷 정지
		// 오탐·쿨다운 어긋남이 생기지 않도록 하는 것이 목적(HARDENING-ROADMAP C-6).
		// 주의: 이 고정으로 데모 로그의 시각 표기도 UTC로 바뀐다(로컬시각 아님) — 의도된 변화다.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
		SpringApplication.run(DbtowerApplication.class, args);
	}

}
