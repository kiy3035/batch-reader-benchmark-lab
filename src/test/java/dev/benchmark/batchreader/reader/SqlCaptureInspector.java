package dev.benchmark.batchreader.reader;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SqlCaptureInspector implements StatementInspector {

    private static final List<String> SQL = new CopyOnWriteArrayList<>();

    /** Hibernate가 실행하기 직전의 SQL을 보존하고 수정 없이 반환한다. */
    @Override
    public String inspect(String sql) {
        SQL.add(sql);
        return sql;
    }

    /** 앞선 검증에서 저장된 SQL을 모두 지운다. */
    public static void clear() {
        SQL.clear();
    }

    /** 현재까지 수집한 SQL의 변경 불가능한 복사본을 반환한다. */
    public static List<String> statements() {
        return List.copyOf(SQL);
    }
}
