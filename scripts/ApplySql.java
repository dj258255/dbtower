import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL 스크립트를 JDBC로 적용한다 — 컨테이너 이미지에 DB 클라이언트가 없을 때 쓴다(Azure SQL Edge 이미지에는 sqlcmd가 없다).
 *
 * <p>줄 단독 {@code GO}를 배치 구분자로 나눠 순서대로 실행하고, 실패하면 그 배치를 알리고 멈춘다. 비밀번호는 argv에 두지 않고
 * 환경변수 {@code DB_PASSWORD}로 받는다 — argv는 프로세스 목록으로 새기 때문이다(AGENTS.md 비밀값 규칙).
 *
 * <pre>
 * DB_PASSWORD='...' java -cp mssql-jdbc.jar scripts/ApplySql.java "jdbc:sqlserver://127.0.0.1:11433;encrypt=false" sa docker/workbench-mssql.sql
 * </pre>
 */
public class ApplySql {

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("사용법: DB_PASSWORD=... java -cp <jdbc.jar> ApplySql.java <jdbc-url> <user> <script.sql>");
            System.exit(2);
        }
        String password = System.getenv("DB_PASSWORD");
        if (password == null || password.isEmpty()) {
            System.err.println("DB_PASSWORD 환경변수가 필요합니다(비밀번호는 인자로 받지 않습니다)");
            System.exit(2);
        }
        List<String> batches = batches(Files.readString(Path.of(args[2])));
        try (Connection c = DriverManager.getConnection(args[0], args[1], password); Statement st = c.createStatement()) {
            for (int i = 0; i < batches.size(); i++) {
                String batch = batches.get(i);
                try {
                    st.execute(batch);
                    System.out.println("ok " + (i + 1) + "/" + batches.size() + ": " + firstLine(batch));
                } catch (SQLException e) {
                    System.err.println("실패 " + (i + 1) + "/" + batches.size() + ": " + firstLine(batch) + "\n  " + e.getMessage());
                    System.exit(1);
                }
            }
        }
    }

    static List<String> batches(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (line.strip().equalsIgnoreCase("GO")) {
                add(out, current);
            } else {
                current.append(line).append('\n');
            }
        }
        add(out, current);
        return out;
    }

    private static void add(List<String> out, StringBuilder current) {
        String batch = current.toString().strip();
        boolean onlyComments = batch.lines().allMatch(l -> l.isBlank() || l.strip().startsWith("--"));
        if (!batch.isEmpty() && !onlyComments) {
            out.add(batch);
        }
        current.setLength(0);
    }

    private static String firstLine(String batch) {
        return batch.lines().filter(l -> !l.isBlank() && !l.strip().startsWith("--")).findFirst().orElse("").strip();
    }
}
