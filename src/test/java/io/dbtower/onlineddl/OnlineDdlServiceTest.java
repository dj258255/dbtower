package io.dbtower.onlineddl;

import io.dbtower.onlineddl.internal.OnlineDdlResult;
import io.dbtower.onlineddl.internal.OnlineDdlService;
import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import io.dbtower.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * gh-ost 오케스트레이션의 3-값 정직성 검증 — 실제 gh-ost 없이도 UNSUPPORTED/FAILED 분기를 못 박는다.
 * UNSUPPORTED를 OK로 위장하지 않는 규칙이 코드로 고정돼 있는지 확인한다.
 */
class OnlineDdlServiceTest {

    private final RegistryService registryService = Mockito.mock(RegistryService.class);

    private OnlineDdlService serviceWithGhost(String ghostCommand) {
        return new OnlineDdlService(registryService, ghostCommand,
                "--allow-on-master --assume-rbr", 60);
    }

    private void stub(Long id, DbmsType type) {
        when(registryService.findById(id)).thenReturn(
                new DatabaseInstance("i", type, "127.0.0.1", 13306, "sample", "root", "pw"));
    }

    @Test
    void 비_MySQL_기종은_UNSUPPORTED다() {
        stub(1L, DbmsType.POSTGRESQL);
        // gh-ost 존재 여부와 무관하게 기종에서 먼저 걸러진다
        OnlineDdlResult r = serviceWithGhost("gh-ost").run(1L, "t", "ADD COLUMN x INT", false);
        assertEquals(OnlineDdlResult.Status.UNSUPPORTED, r.status());
        assertTrue(r.detail().contains("MySQL 전용"), r.detail());
    }

    @Test
    void MySQL이라도_바이너리가_없으면_UNSUPPORTED다() {
        stub(2L, DbmsType.MYSQL);
        OnlineDdlResult r = serviceWithGhost("gh-ost-nonexistent-xyz")
                .run(2L, "b4_demo", "ADD COLUMN x INT NULL", false);
        assertEquals(OnlineDdlResult.Status.UNSUPPORTED, r.status());
        assertTrue(r.detail().contains("바이너리를 찾을 수 없습니다"), r.detail());
    }

    @Test
    void 세미콜론_문장_스택_주입은_FAILED로_막고_실행까지_가지_않는다() {
        stub(3L, DbmsType.MYSQL);
        // 바이너리 사전점검을 통과시키기 위해 존재하는 무해한 명령(true는 항상 exit 0)을 gh-ost 자리에 둔다.
        // ALTER 검증에서 세미콜론이 걸리면 exec까지 가지 않으므로 실제 명령은 실행되지 않는다.
        OnlineDdlResult r = new OnlineDdlService(registryService, "true", "", 60)
                .run(3L, "b4_demo", "ADD COLUMN x INT; DROP TABLE users", false);
        assertEquals(OnlineDdlResult.Status.FAILED, r.status());
        assertTrue(r.detail().contains("세미콜론"), r.detail());
    }
}
