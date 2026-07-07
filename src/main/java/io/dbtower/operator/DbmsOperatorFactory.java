package io.dbtower.operator;

import io.dbtower.registry.DatabaseInstance;
import org.springframework.stereotype.Component;

/**
 * 등록된 인스턴스의 기종(type)을 보고 맞는 Operator 구현체를 골라준다.
 * 플랫폼의 나머지 코드는 이 팩토리와 DbmsOperator 인터페이스만 알면 된다.
 */
@Component
public class DbmsOperatorFactory {

    private final ConnectionPools pools;
    private final MongoClientCache mongoClients;
    private final BackupTools backupTools;
    private final HistogramSnapshotStore histogramStore;
    /** Oracle 통계 필터 대상 앱 스키마(C-4) — 비면 시스템 스키마만 제외. Operator는 빈이 아니라 여기서 전달한다. */
    private final String oracleAppSchema;

    public DbmsOperatorFactory(ConnectionPools pools, MongoClientCache mongoClients,
                               HistogramSnapshotStore histogramStore,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.oracle.app-schema:}") String oracleAppSchema,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.mysqldump-command:mysqldump}") String mysqldumpCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.pg-dump-command:pg_dump}") String pgDumpCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.mongodump-command:mongodump}") String mongodumpCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.mysql-restore-command:mysql}") String mysqlRestoreCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.pg-restore-command:psql}") String pgRestoreCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.mongo-restore-command:mongorestore}") String mongoRestoreCommand,
                               @org.springframework.beans.factory.annotation.Value("${dbtower.backup.dir:./backups}") String backupDir) {
        this.pools = pools;
        this.mongoClients = mongoClients;
        this.histogramStore = histogramStore;
        this.oracleAppSchema = oracleAppSchema;
        this.backupTools = new BackupTools(mysqldumpCommand, pgDumpCommand, mongodumpCommand,
                mysqlRestoreCommand, pgRestoreCommand, mongoRestoreCommand, backupDir);
    }

    public DbmsOperator create(DatabaseInstance instance) {
        return switch (instance.getType()) {
            case MYSQL -> new MySqlOperator(instance, pools, backupTools, histogramStore);
            case POSTGRESQL -> new PostgresOperator(instance, pools, backupTools);
            case MSSQL -> new MsSqlOperator(instance, pools, backupTools);
            case ORACLE -> new OracleOperator(instance, pools, backupTools, oracleAppSchema);
            case MONGODB -> new MongoOperator(instance, mongoClients, backupTools, histogramStore); // 비 JDBC — 풀 대신 클라이언트 캐시
        };
    }
}
