package dev.benchmark.batchreader.experiment;

import dev.benchmark.batchreader.benchmark.IndexMode;
import dev.benchmark.batchreader.benchmark.ReaderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class IndexAndExplainIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine")
            .withDatabaseName("batch_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark_local");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ExperimentIndexManager indexManager;
    @Autowired private ExplainCollector explainCollector;
    @TempDir Path outputDirectory;

    /** Testcontainers PostgreSQL 접속 정보를 Spring 설정에 연결한다. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 각 테스트 전에 앞·중간·부분 마지막 페이지가 있는 결정적 데이터를 만든다. */
    @BeforeEach
    void prepareDataset() {
        jdbcTemplate.queryForObject("SELECT prepare_settlement_seed(?)", Long.class, 3001);
    }

    /** 보조 인덱스 ON/OFF가 카탈로그 정의와 일치하고 ANALYZE가 통계를 갱신하는지 확인한다. */
    @Test
    void switchesAndVerifiesAuxiliaryIndex() {
        ExperimentIndexState on = indexManager.apply(IndexMode.ON);

        assertThat(on.verified()).isTrue();
        assertThat(on.definition()).contains(ExperimentIndexManager.INDEX_NAME).contains("(status, id)");
        assertThat(indexManager.verify(IndexMode.ON)).isEqualTo(on);
        Double estimatedRows = jdbcTemplate.queryForObject("""
                SELECT reltuples FROM pg_class
                WHERE oid = 'settlement_item'::regclass
                """, Double.class);
        assertThat(estimatedRows).isGreaterThan(3000.0);

        ExperimentIndexState off = indexManager.apply(IndexMode.OFF);
        assertThat(off.verified()).isTrue();
        assertThat(off.definition()).isNull();
        assertThat(indexManager.verify(IndexMode.OFF)).isEqualTo(off);
    }

    /** 두 인덱스 모드에서 앞·중간·마지막 OFFSET/Keyset 계획과 바인딩을 JSON으로 보존하는지 확인한다. */
    @Test
    void capturesComparableExplainArtifacts() throws Exception {
        List<ExplainArtifact> offArtifacts = explainCollector.collect(3001, "READY",
                indexManager.apply(IndexMode.OFF), outputDirectory);
        List<ExplainArtifact> onArtifacts = explainCollector.collect(3001, "READY",
                indexManager.apply(IndexMode.ON), outputDirectory);

        assertThat(offArtifacts).hasSize(6).allSatisfy(this::assertCommonMetrics);
        assertThat(onArtifacts).hasSize(6).allSatisfy(this::assertCommonMetrics);
        try (var files = Files.list(outputDirectory)) {
            assertThat(files.filter(path -> path.toString().endsWith(".json"))).hasSize(12);
        }

        ExplainArtifact offsetMiddle = find(offArtifacts, ReaderType.OFFSET, ExplainPosition.MIDDLE);
        ExplainArtifact keysetMiddle = find(offArtifacts, ReaderType.KEYSET, ExplainPosition.MIDDLE);
        ExplainArtifact offsetLast = find(onArtifacts, ReaderType.OFFSET, ExplainPosition.LAST);
        ExplainArtifact keysetLast = find(onArtifacts, ReaderType.KEYSET, ExplainPosition.LAST);

        assertThat(offsetMiddle.pageOffset()).isEqualTo(1000L);
        assertThat(offsetMiddle.bindings()).containsEntry("offset", 1000L);
        assertThat(keysetMiddle.correspondingLastId()).isEqualTo(1000L);
        assertThat(keysetMiddle.bindings()).containsEntry("lastId", 1000L);
        assertThat(offsetLast.pageOffset()).isEqualTo(3000L);
        assertThat(keysetLast.correspondingLastId()).isEqualTo(3000L);
        assertThat(offsetMiddle.sql()).contains("LIMIT ? OFFSET ?");
        assertThat(keysetMiddle.sql()).contains("id > ?").doesNotContain("OFFSET ?");
        assertThat(onArtifacts).allSatisfy(artifact ->
                assertThat(artifact.indexDefinition()).contains(ExperimentIndexManager.INDEX_NAME));
    }

    /** 공통 EXPLAIN 시간, 원본 JSON과 평탄화한 plan 노드가 채워졌는지 확인한다. */
    private void assertCommonMetrics(ExplainArtifact artifact) {
        assertThat(artifact.indexVerified()).isTrue();
        assertThat(artifact.planningTimeMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(artifact.executionTimeMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(artifact.rawExplain().isArray()).isTrue();
        assertThat(artifact.planNodes()).isNotEmpty().allSatisfy(node -> {
            assertThat(node.nodeType()).isNotBlank();
            assertThat(node.actualLoops()).isGreaterThanOrEqualTo(0L);
        });
    }

    /** Reader와 페이지 위치가 일치하는 단일 산출물을 찾는다. */
    private ExplainArtifact find(List<ExplainArtifact> artifacts, ReaderType readerType,
                                 ExplainPosition position) {
        return artifacts.stream()
                .filter(artifact -> artifact.readerType() == readerType && artifact.position() == position)
                .findFirst()
                .orElseThrow();
    }
}
