package io.dbtower.operator.internal;

import io.dbtower.operator.ConnectionPools;
import io.dbtower.operator.DbmsOperator;
import io.dbtower.operator.VaultCredentials;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.HealthStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 대상이 TCP 연결은 받지만 프로토콜 응답을 한 바이트도 주지 않을 때(죽은 포트 포워드, 멈춘 프록시, 반쯤 열린 연결) 플랫폼 스레드가 묶이지 않는다.
 *
 * <p>134절 실측: 삭제된 VM의 포트 포워드가 연결만 받아 주자 SQL Server 풀 생성이 prelogin 읽기에 7분 넘게 매달렸고, 그 생성이
 * {@code ConcurrentHashMap.computeIfAbsent} 안이라 같은 인스턴스를 부르던 폴러(운영 경보·헬스 스코어·설정 드리프트)와 삭제 API가 함께 멈췄다.
 */
class UnresponsiveTargetTest {

    private ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final ConnectionPools pools = new ConnectionPools(new VaultCredentials("", ""),
            15, 6, 2000, 600_000, 1_800_000, 30, 60_000);

    @BeforeEach
    void silentServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    accepted.add(server.accept()); // 받기만 하고 아무것도 쓰지 않는다
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket s : accepted) {
            s.close();
        }
        server.close();
        pools.closeAll();
    }

    private DatabaseInstance instance(long id, DbmsType type) {
        DatabaseInstance i = new DatabaseInstance("silent-" + id, type, "127.0.0.1", server.getLocalPort(),
                type == DbmsType.ORACLE ? "FREEPDB1" : "sample", "u", "p");
        ReflectionTestUtils.setField(i, "id", id);
        return i;
    }

    private DbmsOperator operator(DatabaseInstance i) {
        return switch (i.getType()) {
            case MYSQL -> new MySqlOperator(i, pools, null, null);
            case POSTGRESQL -> new PostgresOperator(i, pools, null);
            case MSSQL -> new MsSqlOperator(i, pools, null);
            case ORACLE -> new OracleOperator(i, pools, null);
            case MONGODB -> new MongoOperator(i, new MongoClientCache(pools), null, null);
        };
    }

    @ParameterizedTest
    @EnumSource(DbmsType.class)
    void 응답하지_않는_대상의_헬스체크는_제한_시간_안에_down으로_끝난다(DbmsType type) {
        DbmsOperator op = operator(instance(9900 + type.ordinal(), type));

        long t0 = System.nanoTime();
        HealthStatus health = assertTimeoutPreemptively(Duration.ofSeconds(25), op::health,
                type + " 헬스체크가 응답 없는 대상에 매달렸다");
        System.out.printf("[무응답 대상 %s] up=%s elapsed_ms=%d message=%s%n", type, health.up(),
                (System.nanoTime() - t0) / 1_000_000, health.message());
        assertFalse(health.up());
        // 풀이 첫 연결을 늦게 열어도 다운 사유가 "커넥션을 못 얻었다"로만 뭉개지지 않는다 — 드라이버의 실제 사유가 붙는다
        assertNotEquals("Failed to obtain JDBC Connection", health.message());
    }

    @ParameterizedTest
    @EnumSource(value = DbmsType.class, names = {"MSSQL", "ORACLE"})
    void 매달린_첫_연결이_같은_인스턴스의_다음_호출과_삭제를_막지_않는다(DbmsType type) throws Exception {
        DatabaseInstance silent = instance(9950 + type.ordinal(), type);
        Thread.ofVirtual().start(() -> operator(silent).health()); // 풀 생성·첫 연결이 매달리는 쪽
        Thread.sleep(800);

        long t0 = System.nanoTime();
        HealthStatus second = assertTimeoutPreemptively(Duration.ofSeconds(25), () -> operator(silent).health(),
                "먼저 매달린 풀 생성 뒤에 같은 인스턴스의 헬스체크가 줄을 섰다");
        long secondMs = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(second.up());

        long t1 = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> pools.close(silent.getId()),
                "매달린 풀 생성이 인스턴스 삭제(풀 정리)를 막았다");
        System.out.printf("[무응답 대상 %s 동시] 두 번째 헬스체크 %dms, 풀 정리 %dms%n", type, secondMs,
                (System.nanoTime() - t1) / 1_000_000);
    }
}
