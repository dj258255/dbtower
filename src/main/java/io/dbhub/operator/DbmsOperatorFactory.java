package io.dbhub.operator;

import io.dbhub.registry.DatabaseInstance;
import org.springframework.stereotype.Component;

/**
 * 등록된 인스턴스의 기종(type)을 보고 맞는 Operator 구현체를 골라준다.
 * 플랫폼의 나머지 코드는 이 팩토리와 DbmsOperator 인터페이스만 알면 된다.
 */
@Component
public class DbmsOperatorFactory {

    private final ConnectionPools pools;
    private final BackupTools backupTools;

    public DbmsOperatorFactory(ConnectionPools pools,
                               @org.springframework.beans.factory.annotation.Value("${dbhub.backup.mysqldump-command:mysqldump}") String mysqldumpCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbhub.backup.pg-dump-command:pg_dump}") String pgDumpCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbhub.backup.dir:./backups}") String backupDir) {
        this.pools = pools;
        this.backupTools = new BackupTools(mysqldumpCommand, pgDumpCommand, backupDir);
    }

    public DbmsOperator create(DatabaseInstance instance) {
        return switch (instance.getType()) {
            case MYSQL -> new MySqlOperator(instance, pools, backupTools);
            case POSTGRESQL -> new PostgresOperator(instance, pools, backupTools);
            case MSSQL -> new MsSqlOperator(instance, pools, backupTools);
        };
    }
}
