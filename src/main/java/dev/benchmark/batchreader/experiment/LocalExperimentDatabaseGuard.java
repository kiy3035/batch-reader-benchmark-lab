package dev.benchmark.batchreader.experiment;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

public class LocalExperimentDatabaseGuard {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private final DataSource dataSource;

    /** 검사할 JDBC 연결을 제공하는 DataSource를 저장한다. */
    public LocalExperimentDatabaseGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 인덱스 변경과 EXPLAIN 대상이 로컬 실험 DB인지 확인한다. */
    public void verify() {
        try (Connection connection = dataSource.getConnection()) {
            verifyJdbcUrl(connection.getMetaData().getURL());
        } catch (SQLException exception) {
            throw new IllegalStateException("로컬 실험 DB 연결 정보를 확인하지 못했습니다.", exception);
        }
    }

    /** PostgreSQL JDBC URL의 호스트와 데이터베이스 이름을 검사한다. */
    static void verifyJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("PostgreSQL JDBC URL만 실험에 사용할 수 있습니다: " + jdbcUrl);
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        String host = uri.getHost() == null ? "" : uri.getHost().replace("[", "").replace("]", "");
        String databaseName = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!LOCAL_HOSTS.contains(host) || !"batch_benchmark".equals(databaseName)) {
            throw new IllegalStateException("인덱스 변경과 EXPLAIN은 로컬 batch_benchmark DB에서만 허용됩니다: "
                    + jdbcUrl);
        }
    }
}
