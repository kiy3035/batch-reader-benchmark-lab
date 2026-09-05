package dev.benchmark.batchreader.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.benchmark.batchreader.benchmark.IndexMode;
import dev.benchmark.batchreader.benchmark.ReaderType;
import dev.benchmark.batchreader.reader.ReaderConstants;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ExplainCollector {

    private static final String OFFSET_SQL = """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
            SELECT id, status, customer_id, amount, settled_at, reference_no, payload
            FROM settlement_item
            WHERE status = ?
            ORDER BY id
            LIMIT ? OFFSET ?
            """;
    private static final String KEYSET_SQL = """
            EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
            SELECT id, status, customer_id, amount, settled_at, reference_no, payload
            FROM settlement_item
            WHERE status = ? AND id > ?
            ORDER BY id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LocalExperimentDatabaseGuard databaseGuard;
    private final ExperimentIndexManager indexManager;

    /** EXPLAIN 실행, JSON 변환과 로컬 DB 검사를 수행할 객체를 저장한다. */
    public ExplainCollector(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                            LocalExperimentDatabaseGuard databaseGuard, ExperimentIndexManager indexManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.databaseGuard = databaseGuard;
        this.indexManager = indexManager;
    }

    /** 앞·중간·마지막 페이지의 OFFSET/Keyset 실행계획을 수집해 JSON으로 저장한다. */
    public List<ExplainArtifact> collect(long targetRows, String status, ExperimentIndexState indexState,
                                         Path outputDirectory) {
        databaseGuard.verify();
        validateDataset(targetRows, status);
        ExperimentIndexState verifiedIndexState = indexManager.verify(indexState.mode());
        Map<ExplainPosition, Long> positions = positions(targetRows);
        List<ExplainArtifact> artifacts = new ArrayList<>();
        for (Map.Entry<ExplainPosition, Long> entry : positions.entrySet()) {
            long offset = entry.getValue();
            long lastId = correspondingLastId(status, offset);
            artifacts.add(explainOffset(targetRows, status, verifiedIndexState, entry.getKey(), offset, lastId));
            artifacts.add(explainKeyset(targetRows, status, verifiedIndexState, entry.getKey(), offset, lastId));
        }
        writeArtifacts(artifacts, outputDirectory);
        return List.copyOf(artifacts);
    }

    /** READY 등 대상 상태의 실제 건수가 요청 규모와 같은지 확인한다. */
    private void validateDataset(long targetRows, String status) {
        if (targetRows <= 0) {
            throw new IllegalArgumentException("targetRows는 1 이상이어야 합니다.");
        }
        Long actual = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM settlement_item WHERE status = ?", Long.class, status);
        if (actual == null || actual != targetRows) {
            throw new IllegalStateException("대상 데이터 건수가 요청 규모와 다릅니다. expected="
                    + targetRows + ", actual=" + actual);
        }
    }

    /** pageSize 경계에 맞춘 첫·중간·마지막 페이지 OFFSET을 계산한다. */
    private Map<ExplainPosition, Long> positions(long targetRows) {
        long pageSize = ReaderConstants.PAGE_SIZE;
        Map<ExplainPosition, Long> positions = new LinkedHashMap<>();
        positions.put(ExplainPosition.FIRST, 0L);
        positions.put(ExplainPosition.MIDDLE, (targetRows / 2 / pageSize) * pageSize);
        positions.put(ExplainPosition.LAST, ((targetRows - 1) / pageSize) * pageSize);
        return positions;
    }

    /** OFFSET 페이지 바로 앞 행의 ID를 구해 대응하는 Keyset 바인딩으로 사용한다. */
    private long correspondingLastId(String status, long offset) {
        if (offset == 0) {
            return 0L;
        }
        Long lastId = jdbcTemplate.queryForObject("""
                SELECT id FROM settlement_item
                WHERE status = ?
                ORDER BY id
                LIMIT 1 OFFSET ?
                """, Long.class, status, offset - 1);
        if (lastId == null) {
            throw new IllegalStateException("OFFSET에 대응하는 lastId를 찾지 못했습니다: " + offset);
        }
        return lastId;
    }

    /** 지정 위치에서 OFFSET SQL의 실행계획과 실제 바인딩을 수집한다. */
    private ExplainArtifact explainOffset(long targetRows, String status, ExperimentIndexState indexState,
                                           ExplainPosition position, long offset, long lastId) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("status", status);
        bindings.put("pageSize", ReaderConstants.PAGE_SIZE);
        bindings.put("offset", offset);
        JsonNode raw = executeExplain(OFFSET_SQL, status, ReaderConstants.PAGE_SIZE, offset);
        return artifact(targetRows, ReaderType.OFFSET, indexState, position, offset, lastId,
                OFFSET_SQL, bindings, raw);
    }

    /** 지정 위치에서 Keyset SQL의 실행계획과 실제 바인딩을 수집한다. */
    private ExplainArtifact explainKeyset(long targetRows, String status, ExperimentIndexState indexState,
                                           ExplainPosition position, long offset, long lastId) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("status", status);
        bindings.put("lastId", lastId);
        bindings.put("pageSize", ReaderConstants.PAGE_SIZE);
        JsonNode raw = executeExplain(KEYSET_SQL, status, lastId, ReaderConstants.PAGE_SIZE);
        return artifact(targetRows, ReaderType.KEYSET, indexState, position, offset, lastId,
                KEYSET_SQL, bindings, raw);
    }

    /** PreparedStatement로 EXPLAIN을 실행하고 PostgreSQL JSON 결과를 파싱한다. */
    private JsonNode executeExplain(String sql, Object... parameters) {
        String json = jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(sql);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement;
        }, resultSet -> {
            if (!resultSet.next()) {
                throw new IllegalStateException("EXPLAIN 결과가 비어 있습니다.");
            }
            return resultSet.getString(1);
        });
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new IllegalStateException("PostgreSQL EXPLAIN JSON을 읽지 못했습니다.", exception);
        }
    }

    /** 원본 실행계획에서 상단 시간과 재귀 노드 지표를 추출해 산출물을 만든다. */
    private ExplainArtifact artifact(long targetRows, ReaderType readerType, ExperimentIndexState indexState,
                                     ExplainPosition position, long offset, long lastId, String sql,
                                     Map<String, Object> bindings, JsonNode raw) {
        JsonNode root = raw.path(0);
        List<ExplainPlanNode> planNodes = new ArrayList<>();
        collectPlanNodes(root.path("Plan"), planNodes);
        return new ExplainArtifact(Instant.now(), targetRows, readerType, indexState.mode(), indexState.verified(),
                indexState.definition(), position, offset, lastId, sql.strip(), Map.copyOf(bindings),
                root.path("Planning Time").asDouble(), root.path("Execution Time").asDouble(),
                List.copyOf(planNodes), raw);
    }

    /** 실행계획 트리를 순회하며 scan, rows, loops, buffers와 sort 정보를 평탄화한다. */
    private void collectPlanNodes(JsonNode plan, List<ExplainPlanNode> nodes) {
        if (plan.isMissingNode()) {
            return;
        }
        List<String> sortKey = new ArrayList<>();
        plan.path("Sort Key").forEach(value -> sortKey.add(value.asText()));
        nodes.add(new ExplainPlanNode(
                textOrNull(plan, "Node Type"), textOrNull(plan, "Relation Name"),
                textOrNull(plan, "Index Name"), plan.path("Plan Rows").asLong(),
                plan.path("Actual Rows").asLong(), plan.path("Actual Loops").asLong(),
                plan.path("Rows Removed by Filter").asLong(), plan.path("Rows Removed by Index Recheck").asLong(),
                plan.path("Shared Hit Blocks").asLong(),
                plan.path("Shared Read Blocks").asLong(), List.copyOf(sortKey), textOrNull(plan, "Sort Method")));
        plan.path("Plans").forEach(child -> collectPlanNodes(child, nodes));
    }

    /** JSON 문자열 필드가 없거나 null이면 Java null을 반환한다. */
    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 각 실행계획을 조건을 식별할 수 있는 고정 파일명으로 기록한다. */
    private void writeArtifacts(List<ExplainArtifact> artifacts, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
            for (ExplainArtifact artifact : artifacts) {
                String fileName = String.format(Locale.ROOT, "%d-%s-%s-%s.json", artifact.targetRows(),
                        artifact.readerType().name().toLowerCase(Locale.ROOT),
                        artifact.indexMode().name().toLowerCase(Locale.ROOT),
                        artifact.position().name().toLowerCase(Locale.ROOT));
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValue(outputDirectory.resolve(fileName).toFile(), artifact);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("EXPLAIN 산출물 저장에 실패했습니다.", exception);
        }
    }
}
