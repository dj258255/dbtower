package io.dbhub.operator;

import io.dbhub.registry.DatabaseInstance;
import org.springframework.stereotype.Component;

/**
 * 등록된 인스턴스의 기종(type)을 보고 맞는 Operator 구현체를 골라준다.
 * 플랫폼의 나머지 코드는 이 팩토리와 DbmsOperator 인터페이스만 알면 된다.
 */
@Component
public class DbmsOperatorFactory {

    public DbmsOperator create(DatabaseInstance instance) {
        return switch (instance.getType()) {
            case MYSQL -> new MySqlOperator(instance);
            case POSTGRESQL -> new PostgresOperator(instance);
            case MSSQL -> new MsSqlOperator(instance);
        };
    }
}
