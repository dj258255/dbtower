package io.dbtower.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dbtower.registry.DatabaseInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Vault 동적 자격증명 해석 (하드닝 잔여 — 정적 모니터링 비밀번호의 수명 문제).
 *
 * 정적 계정은 유출되면 바꿀 때까지 유효하다. Vault database secrets engine은 접속할 때마다
 * 수명 있는 계정을 새로 발급하고(예: TTL 2분~수시간) 만료되면 DB에서 자동 소멸한다 —
 * 유출 피해 창이 "발각~수동 회전"에서 "TTL"로 줄어든다.
 *
 * 사용법: 인스턴스 등록 시 username에 "vault:<creds 경로>"(예: vault:database/creds/dbtower-monitor)를
 * 넣으면, 접속 시점에 이 해석기가 Vault에서 실제 계정을 받아 쓴다(등록 폼의 password는 미사용 더미).
 * 리스의 80%가 지나면 새 계정을 받아오고, ConnectionPools가 계정 변경을 감지해 풀을 갈아끼운다 —
 * 옛 커넥션은 maxLifetime과 계정 만료가 자연 정리한다.
 *
 * 게이트(정직): dbtower.vault.url 미설정이면 이 접두를 쓰는 인스턴스는 접속 시점에 명확히 실패한다
 * (조용히 접두 문자열을 계정명으로 쓰는 오동작 방지). 적용 범위는 JDBC 접속(풀) — 백업 CLI 템플릿의
 * {user}는 여전히 등록 값을 렌더하므로, 동적 자격증명 인스턴스의 백업은 별도 정적 백업 계정을
 * 템플릿에 직접 두는 것을 전제로 한다(한계 명시).
 */
@Component
public class VaultCredentials {

    private static final Logger log = LoggerFactory.getLogger(VaultCredentials.class);

    public static final String PREFIX = "vault:";

    /** creds 경로 화이트리스트 형식 — URL 경로로 들어가므로 경로 조작 문자를 거부한다 */
    private static final Pattern SAFE_PATH = Pattern.compile("[a-zA-Z0-9/_-]+");

    public record Creds(String username, String password) {
    }

    private record Lease(Creds creds, long refreshAtMs) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Lease> leases = new ConcurrentHashMap<>();

    private final String vaultUrl;
    private final String vaultToken;

    public VaultCredentials(@Value("${dbtower.vault.url:}") String vaultUrl,
                            @Value("${dbtower.vault.token:}") String vaultToken) {
        this.vaultUrl = vaultUrl == null ? "" : vaultUrl.replaceAll("/+$", "");
        this.vaultToken = vaultToken == null ? "" : vaultToken;
    }

    /** 이 인스턴스가 Vault 동적 자격증명 대상인가 — username 접두 규약. */
    public boolean applies(DatabaseInstance instance) {
        return instance.getUsername() != null && instance.getUsername().startsWith(PREFIX);
    }

    /**
     * 접속에 쓸 실제 자격증명 — 리스가 살아 있으면 캐시, 80%를 지나면 새로 발급받는다.
     * 등록 검증(id=null) 등 캐시 키가 없으면 매번 발급(짧은 호출 경로라 무해).
     */
    public Creds resolve(DatabaseInstance instance) {
        if (vaultUrl.isBlank() || vaultToken.isBlank()) {
            throw new OperatorException(
                    "username이 vault: 접두인데 Vault 미설정(dbtower.vault.url/token) — 동적 자격증명을 해석할 수 없다", null);
        }
        Long id = instance.getId();
        long now = System.currentTimeMillis();
        if (id != null) {
            Lease lease = leases.get(id);
            if (lease != null && now < lease.refreshAtMs()) {
                return lease.creds();
            }
        }
        Creds fresh = fetch(instance.getUsername().substring(PREFIX.length()));
        if (id != null) {
            // lease_duration의 80% 시점에 선제 갱신 — 만료된 계정으로 접속을 시도하는 창을 없앤다
            leases.put(id, new Lease(fresh, now + lastLeaseMs * 8 / 10));
            log.info("Vault 동적 자격증명 발급 — instance={} user={} (TTL {}s)",
                    instance.getName(), fresh.username(), lastLeaseMs / 1000);
        }
        return fresh;
    }

    /** 마지막 발급의 lease(ms) — fetch가 채운다(단일 스레드 가정 아님: 근사값이면 충분). */
    private volatile long lastLeaseMs = 60_000;

    private Creds fetch(String credsPath) {
        if (!SAFE_PATH.matcher(credsPath).matches()) {
            throw new OperatorException("vault 경로 형식 위반(영문/숫자/슬래시/밑줄/하이픈만): " + credsPath, null);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(vaultUrl + "/v1/" + credsPath))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Vault-Token", vaultToken)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new OperatorException("Vault 자격증명 발급 실패(HTTP " + res.statusCode() + "): " + credsPath, null);
            }
            JsonNode body = mapper.readTree(res.body());
            lastLeaseMs = Math.max(10_000, body.path("lease_duration").asLong(60) * 1000);
            return new Creds(body.path("data").path("username").asText(),
                    body.path("data").path("password").asText());
        } catch (OperatorException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperatorException("Vault 호출 중단", e);
        } catch (Exception e) {
            throw new OperatorException("Vault 호출 실패: " + e.getMessage(), e);
        }
    }

    /** 인스턴스 삭제·풀 정리 시 리스 캐시도 비운다. */
    public void evict(Long instanceId) {
        if (instanceId != null) {
            leases.remove(instanceId);
        }
    }
}
