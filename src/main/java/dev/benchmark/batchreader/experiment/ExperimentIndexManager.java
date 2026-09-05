package dev.benchmark.batchreader.experiment;

import dev.benchmark.batchreader.benchmark.IndexMode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public class ExperimentIndexManager {

    public static final String INDEX_NAME = "idx_settlement_item_status_id";
    private static final String EXPECTED_COLUMNS = "status,id";

    private final JdbcTemplate jdbcTemplate;
    private final LocalExperimentDatabaseGuard databaseGuard;

    /** 인덱스 DDL과 로컬 DB 검사를 수행할 협력 객체를 저장한다. */
    public ExperimentIndexManager(JdbcTemplate jdbcTemplate, LocalExperimentDatabaseGuard databaseGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseGuard = databaseGuard;
    }

    /** 요청 모드에 맞게 보조 인덱스를 전환하고 ANALYZE 후 실제 상태를 검증한다. */
    public ExperimentIndexState apply(IndexMode mode) {
        databaseGuard.verify();
        if (mode == IndexMode.ON) {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + INDEX_NAME
                    + " ON settlement_item USING btree (status, id)");
        } else {
            jdbcTemplate.execute("DROP INDEX IF EXISTS " + INDEX_NAME);
        }
        jdbcTemplate.execute("ANALYZE settlement_item");
        return verify(mode);
    }

    /** PostgreSQL 카탈로그에서 이름, 컬럼 순서, 유효성과 고유 여부를 확인한다. */
    public ExperimentIndexState verify(IndexMode expectedMode) {
        List<CatalogIndex> indexes = jdbcTemplate.query("""
                SELECT pg_get_indexdef(index_relation.oid) AS definition,
                       index_meta.indisvalid,
                       index_meta.indisready,
                       index_meta.indisunique,
                       access_method.amname,
                       pg_get_expr(index_meta.indpred, table_relation.oid) AS predicate,
                       string_agg(attribute.attname, ',' ORDER BY key_column.ordinality) AS columns
                FROM pg_class table_relation
                JOIN pg_namespace namespace ON namespace.oid = table_relation.relnamespace
                JOIN pg_index index_meta ON index_meta.indrelid = table_relation.oid
                JOIN pg_class index_relation ON index_relation.oid = index_meta.indexrelid
                JOIN pg_am access_method ON access_method.oid = index_relation.relam
                CROSS JOIN LATERAL unnest(index_meta.indkey) WITH ORDINALITY key_column(attnum, ordinality)
                JOIN pg_attribute attribute
                  ON attribute.attrelid = table_relation.oid AND attribute.attnum = key_column.attnum
                WHERE namespace.nspname = current_schema()
                  AND table_relation.relname = 'settlement_item'
                  AND index_relation.relname = ?
                GROUP BY index_relation.oid, table_relation.oid, index_meta.indisvalid, index_meta.indisready,
                         index_meta.indisunique, access_method.amname, index_meta.indpred
                """, (resultSet, rowNumber) -> new CatalogIndex(
                resultSet.getString("definition"),
                resultSet.getBoolean("indisvalid"),
                resultSet.getBoolean("indisready"),
                resultSet.getBoolean("indisunique"),
                resultSet.getString("amname"),
                resultSet.getString("predicate"),
                resultSet.getString("columns")), INDEX_NAME);

        if (expectedMode == IndexMode.OFF) {
            if (!indexes.isEmpty()) {
                throw new IllegalStateException("OFF 모드인데 보조 인덱스가 남아 있습니다: " + indexes.getFirst().definition());
            }
            return new ExperimentIndexState(IndexMode.OFF, true, null);
        }
        if (indexes.size() != 1) {
            throw new IllegalStateException("ON 모드의 보조 인덱스가 정확히 하나여야 합니다.");
        }
        CatalogIndex index = indexes.getFirst();
        if (!index.valid() || !index.ready() || index.unique() || !"btree".equals(index.accessMethod())
                || index.predicate() != null || !EXPECTED_COLUMNS.equals(index.columns())) {
            throw new IllegalStateException("보조 인덱스 정의가 실험 조건과 다릅니다: " + index.definition());
        }
        return new ExperimentIndexState(IndexMode.ON, true, index.definition());
    }

    private record CatalogIndex(String definition, boolean valid, boolean ready, boolean unique,
                                String accessMethod, String predicate, String columns) {
    }
}
