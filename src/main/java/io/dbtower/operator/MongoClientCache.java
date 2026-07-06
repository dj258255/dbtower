package io.dbtower.operator;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.dbtower.registry.DatabaseInstance;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB 클라이언트 캐시 — ConnectionPools의 MongoDB 버전.
 *
 * MongoClient는 자체적으로 커넥션 풀을 내장하므로(HikariCP 불필요) 인스턴스마다
 * 하나를 만들어 재사용하는 것이 드라이버 권장 방식이다. JDBC 계열과 마찬가지로
 * 등록 검증 단계(id 없음)는 캐시하지 않고 1회용으로 만들어 쓰고 버린다.
 */
@Component
public class MongoClientCache {

    private final Map<Long, MongoClient> clients = new ConcurrentHashMap<>();

    /**
     * A9: Mongo 조회의 소켓 read 상한(초). JDBC의 setQueryTimeout과 같은 목적 —
     * 진단이 대상 DB를 오래 붙잡지 않게. 같은 dbtower.query-timeout-seconds 설정을 공유한다.
     * 주: 이건 클라이언트측 소켓 상한이다. 서버가 실제로 실행을 오래 도는 무거운 경로(explain
     *   executionStats)는 이와 별개로 명령에 maxTimeMS를 실어 서버측에서 끊는다(MongoOperator 참고).
     *   여기서 CSOT(.timeout())를 쓰지 않는 이유: 클라이언트 레벨 CSOT는 그 명시적 maxTimeMS와
     *   간섭한다(CSOT가 켜지면 드라이버가 수동 maxTimeMS를 무시하고 잔여 예산으로 재계산).
     */
    private final int socketReadTimeoutSeconds;

    public MongoClientCache(ConnectionPools pools) {
        this.socketReadTimeoutSeconds = pools.queryTimeoutSeconds();
    }

    /** id가 있으면 캐시에서, 없으면(등록 전 검증) 1회용으로 생성 — 1회용은 호출자가 닫는다 */
    public MongoClient get(DatabaseInstance instance) {
        if (instance.getId() == null) {
            return create(instance);
        }
        return clients.computeIfAbsent(instance.getId(), id -> create(instance));
    }

    public boolean isEphemeral(DatabaseInstance instance) {
        return instance.getId() == null;
    }

    private MongoClient create(DatabaseInstance instance) {
        // 접속 URI 문자열 대신 빌더를 쓴다 — 비밀번호에 특수문자가 있어도 URL 인코딩 이슈가 없다.
        // authSource=admin 가정: 관리 플랫폼은 admin에 만든 모니터링 계정으로 붙는다(다른 기종의 root/sa/postgres와 동일한 전제)
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b
                        .hosts(List.of(new ServerAddress(instance.getHost(), instance.getPort())))
                        .serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(socketReadTimeoutSeconds, TimeUnit.SECONDS))
                // useTls면 TLS 강제 — Atlas 등 TLS 필수 서비스 대응. 인증서 검증은 JVM truststore 기본
                .applyToSslSettings(b -> b.enabled(instance.isUseTls()))
                .credential(MongoCredential.createCredential(
                        instance.getUsername(), "admin", instance.getPassword().toCharArray()))
                .applicationName("dbtower")
                .build();
        return MongoClients.create(settings);
    }

    /** 인스턴스 삭제 시 클라이언트 정리 — 안 하면 삭제된 대상의 커넥션이 남는다 */
    public void close(Long id) {
        MongoClient client = clients.remove(id);
        if (client != null) {
            client.close();
        }
    }

    @PreDestroy
    public void closeAll() {
        clients.values().forEach(MongoClient::close);
        clients.clear();
    }
}
