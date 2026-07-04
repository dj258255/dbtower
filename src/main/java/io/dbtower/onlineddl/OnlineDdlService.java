package io.dbtower.onlineddl;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * gh-ost 오케스트레이션 (B4) — 대형 테이블 ALTER를 락 최소화로 수행한다.
 *
 * 안전 설계:
 * - 기본은 dry-run(noop). execute=true를 명시적으로 줄 때만 실제 변경한다.
 * - gh-ost는 MySQL 전용 → 다른 기종·바이너리 부재는 UNSUPPORTED(성공 위장 금지).
 * - 비밀번호는 argv가 아니라 소유자 전용 임시 conf 파일로만 전달하고 실행 직후 삭제한다
 *   (한계·이유는 OnlineDdlCommands 주석).
 *
 * 이력은 저장하지 않는다 — dry-run 중심의 진단·실행 도구라 상태가 없고, 누가 언제 호출했는지는
 * 감사 로그(A6, AuditInterceptor)가 이미 남긴다. 그래서 새 테이블/Flyway 마이그레이션도 없다.
 */
@Service
public class OnlineDdlService {

    private static final Logger log = LoggerFactory.getLogger(OnlineDdlService.class);

    private final RegistryService registryService;
    private final List<String> ghostBase;
    private final List<String> ghostFlags;
    private final long timeoutSeconds;

    public OnlineDdlService(
            RegistryService registryService,
            @Value("${dbtower.online-ddl.ghost-command:gh-ost}") String ghostCommand,
            @Value("${dbtower.online-ddl.ghost-flags:--allow-on-master --assume-rbr --initially-drop-ghost-table --ok-to-drop-table}") String ghostFlags,
            @Value("${dbtower.online-ddl.timeout-seconds:1800}") long timeoutSeconds) {
        this.registryService = registryService;
        this.ghostBase = OnlineDdlCommands.tokenize(ghostCommand);
        this.ghostFlags = OnlineDdlCommands.flagsFrom(ghostFlags);
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 온라인 스키마 변경을 수행한다. execute=false면 gh-ost 기본 noop(dry-run)으로 검증만 하고
     * 실제 테이블은 건드리지 않는다. execute=true면 실제로 ALTER를 적용한다(ADMIN 경계는 SecurityConfig).
     */
    public OnlineDdlResult run(Long instanceId, String table, String alter, boolean execute) {
        DatabaseInstance instance = registryService.findById(instanceId); // 존재 검증
        String mode = execute ? "execute" : "noop";

        if (instance.getType() != DbmsType.MYSQL) {
            return OnlineDdlResult.unsupported(
                    "gh-ost는 MySQL 전용입니다 — " + instance.getType() + " 기종은 온라인 스키마 변경을 지원하지 않습니다");
        }
        if (!OnlineDdlCommands.binaryAvailable(ghostBase)) {
            return OnlineDdlResult.unsupported(
                    "gh-ost 바이너리를 찾을 수 없습니다(" + String.join(" ", ghostBase)
                            + ") — 설치 후 다시 시도하세요");
        }

        // 입력 검증은 conf 파일을 만들기 전에 — 잘못된 요청에 파일 부작용이 없게
        try {
            OnlineDdlCommands.validateIdentifier("테이블", table);
            OnlineDdlCommands.validateAlter(alter);
        } catch (IllegalArgumentException e) {
            return OnlineDdlResult.failed(mode, e.getMessage());
        }

        Path conf = OnlineDdlCommands.writeConf(instance);
        try {
            List<String> command = OnlineDdlCommands.buildArgs(
                    ghostBase, ghostFlags, instance, table, alter, conf, execute);
            OnlineDdlCommands.ExecResult result = OnlineDdlCommands.exec(command, timeoutSeconds);
            String ghostTable = OnlineDdlCommands.parseGhostTable(result.output());
            if (result.ok()) {
                String detail = execute
                        ? "실제 실행(execute) 완료 — 테이블 " + table + "에 ALTER 적용됨"
                        : "dry-run(noop) 통과 — 실제 변경 없음. --execute로 적용 가능";
                return OnlineDdlResult.ok(mode, detail, ghostTable);
            }
            log.warn("gh-ost 실패 instance={} table={} mode={}", instanceId, table, mode);
            return OnlineDdlResult.failed(mode, "gh-ost 실패: " + result.tail(600));
        } finally {
            deleteQuietly(conf); // 비밀번호가 든 임시 파일은 성공/실패와 무관하게 즉시 삭제
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("gh-ost 임시 설정 파일 삭제 실패 — 수동 확인 필요: {}", path);
        }
    }
}
