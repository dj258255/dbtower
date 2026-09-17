package io.dbtower.operator.internal;

import io.dbtower.operator.model.RowImage.ImageColumn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowValuesBindTest {

    private final List<String> calls = new ArrayList<>();

    private final PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                calls.add(method.getName() + ":" + args[1].getClass().getSimpleName() + ":" + args[1]);
                return null;
            });

    @Test
    void 정수_열은_long으로_보내_PostgreSQL이_numeric_비교로_인덱스를_잃지_않게_한다() throws Exception {
        for (int type : new int[]{Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT}) {
            RowValues.bind(ps, 1, "42", new ImageColumn("id", type, "int"));
        }

        assertThat(calls).containsOnly("setLong:Long:42");
    }

    @Test
    void long_범위를_넘는_정수는_BigDecimal로_보낸다() throws Exception {
        RowValues.bind(ps, 1, "18446744073709551615", new ImageColumn("id", Types.BIGINT, "BIGINT UNSIGNED"));

        assertThat(calls).containsExactly("setBigDecimal:BigDecimal:18446744073709551615");
    }

    @Test
    void 소수_열은_그대로_BigDecimal로_보내_자릿수를_잃지_않는다() throws Exception {
        RowValues.bind(ps, 1, "12.340", new ImageColumn("amount", Types.NUMERIC, "numeric"));

        assertThat(calls).containsExactly("setBigDecimal:BigDecimal:" + new BigDecimal("12.340"));
    }
}
