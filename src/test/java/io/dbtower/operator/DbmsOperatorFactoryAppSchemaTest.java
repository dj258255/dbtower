package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import io.dbtower.registry.DbmsType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Oracle 앱 스키마는 인스턴스에 등록한 값이 먼저이고, 없을 때만 전역 설정을 쓴다. */
class DbmsOperatorFactoryAppSchemaTest {

    private final DbmsOperatorFactory factory = new DbmsOperatorFactory(null, null, null, "GLOBAL_APP",
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "build/tmp/backups", false);

    @Test
    void 인스턴스에_등록한_앱_스키마가_전역_설정보다_먼저다() {
        DatabaseInstance own = new DatabaseInstance("o1", DbmsType.ORACLE, "h", 1521, "FREEPDB1", "m", "p");
        own.updateAppSchema("SAMPLE");
        DatabaseInstance none = new DatabaseInstance("o2", DbmsType.ORACLE, "h", 1521, "FREEPDB1", "m", "p");
        none.updateAppSchema("  ");

        assertEquals("SAMPLE", factory.appSchemaOf(own));
        assertNull(none.getAppSchema());
        assertEquals("GLOBAL_APP", factory.appSchemaOf(none));
    }
}
