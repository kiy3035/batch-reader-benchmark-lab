package dev.benchmark.batchreader;

import dev.benchmark.batchreader.batch.SettlementChecksumProcessor;
import dev.benchmark.batchreader.batch.SettlementContribution;
import dev.benchmark.batchreader.domain.SettlementItem;
import dev.benchmark.batchreader.reader.JpaKeysetPageSource;
import dev.benchmark.batchreader.reader.KeysetJpaItemReader;
import dev.benchmark.batchreader.reader.OffsetReaderFactory;
import dev.benchmark.batchreader.reader.ReaderConstants;
import dev.benchmark.batchreader.reader.SqlCaptureInspector;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=dev.benchmark.batchreader.reader.SqlCaptureInspector"
})
class ReaderCorrectnessIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine")
            .withDatabaseName("batch_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark_local");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobLauncher jobLauncher;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    /** Testcontainers PostgreSQL 접속 정보를 Spring에 연결한다. */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** 각 테스트가 동일한 smoke 원본에서 시작하도록 합성 테이블을 복원한다. */
    @BeforeEach
    void resetSqlCapture() {
        SqlCaptureInspector.clear();
    }

    /** 0/1/999/1000/1001 READY 행에서 두 Reader의 ID 순서와 checksum이 같은지 확인한다. */
    @Test
    void readsIdenticalRowsForBoundarySizes() throws Exception {
        for (int readyRows : List.of(0, 1, 999, 1000, 1001)) {
            jdbcTemplate.queryForObject("SELECT prepare_settlement_seed(?)", Long.class, readyRows);

            List<SettlementItem> offset = readAll(offsetReader("offsetBoundary" + readyRows));
            List<SettlementItem> keyset = readAll(keysetReader("keysetBoundary" + readyRows));

            assertThat(ids(offset)).containsExactlyElementsOf(ids(keyset));
            assertThat(ids(offset)).doesNotHaveDuplicates().isSorted();
            assertThat(offset).hasSize(readyRows);
            assertThat(checksum(offset)).isEqualTo(checksum(keyset));
        }
    }

    /** ID gap과 다른 상태가 섞인 데이터에서도 READY 행만 누락 없이 읽는지 확인한다. */
    @Test
    void handlesIdGapsAndMixedStatuses() throws Exception {
        jdbcTemplate.update("TRUNCATE TABLE settlement_item");
        jdbcTemplate.update("""
                INSERT INTO settlement_item(id,status,customer_id,amount,settled_at,reference_no,payload)
                VALUES (2,'READY',1,1.00,now(),'R2','{}'),
                       (7,'COMPLETED',1,1.00,now(),'C7','{}'),
                       (11,'READY',1,2.00,now(),'R11','{}'),
                       (1005,'READY',1,3.00,now(),'R1005','{}')
                """);

        assertThat(ids(readAll(offsetReader("offsetGap")))).containsExactly(2L, 11L, 1005L);
        assertThat(ids(readAll(keysetReader("keysetGap")))).containsExactly(2L, 11L, 1005L);
    }

    /** 실제 Hibernate SQL이 OFFSET과 id 조건의 서로 다른 페이징 형태인지 확인한다. */
    @Test
    void generatesOffsetAndKeysetSql() throws Exception {
        jdbcTemplate.queryForObject("SELECT prepare_settlement_seed(?)", Long.class, 1001);
        SqlCaptureInspector.clear();
        JpaPagingItemReader<SettlementItem> offsetReader =
                new OffsetReaderFactory(entityManagerFactory).create("offsetSql");
        readAll(offsetReader);
        List<String> offsetSql = SqlCaptureInspector.statements();

        SqlCaptureInspector.clear();
        readAll(keysetReader("keysetSql"));
        List<String> keysetSql = SqlCaptureInspector.statements();

        assertThat(offsetSql).anySatisfy(sql -> assertThat(sql.toLowerCase())
                .contains("offset ? rows").contains("fetch first ? rows only"));
        assertThat(offsetSql.stream().filter(sql -> sql.toLowerCase().contains("offset ? rows")).count())
                .isGreaterThanOrEqualTo(2L);
        assertThat(keysetSql).anySatisfy(sql -> assertThat(sql.toLowerCase())
                .contains("id>?").contains("fetch first ? rows only").doesNotContain("offset ? rows"));
    }

    /** 의도적 두 번째 chunk 실패 뒤 같은 JobInstance 재시작이 1001개를 정확히 한 번 처리하는지 확인한다. */
    @Test
    void restartsSameJobInstanceWithoutDuplicates() throws Exception {
        jdbcTemplate.queryForObject("SELECT prepare_settlement_seed(?)", Long.class, 1001);
        List<Long> committedIds = new ArrayList<>();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ItemWriter<SettlementContribution> writer = chunk -> {
            boolean containsFailurePoint = chunk.getItems().stream().anyMatch(item -> item.id() == 1001L);
            if (containsFailurePoint && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("재시작 검증을 위한 의도적 실패");
            }
            chunk.getItems().forEach(item -> committedIds.add(item.id()));
        };
        KeysetJpaItemReader reader = keysetReader("restartKeyset");
        Step step = new StepBuilder("restartStep", jobRepository)
                .<SettlementItem, SettlementContribution>chunk(1000, transactionManager)
                .reader(reader).processor(new SettlementChecksumProcessor()).writer(writer).build();
        Job job = new JobBuilder("restartCorrectnessJob", jobRepository).start(step).build();
        JobParameters parameters = new JobParametersBuilder().addString("case", "same-instance").toJobParameters();

        JobExecution failed = jobLauncher.run(job, parameters);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getStepExecutions()).singleElement().satisfies(execution ->
                assertThat(execution.getExecutionContext().getLong("restartKeyset.lastId")).isEqualTo(1000L));

        JobExecution restarted = jobLauncher.run(job, parameters);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(committedIds).containsExactlyElementsOf(expectedIds(1001)).doesNotHaveDuplicates();
    }

    /** OFFSET Reader도 의도적 실패 뒤 같은 JobInstance에서 누락·중복 없이 재시작하는지 확인한다. */
    @Test
    void restartsOffsetReaderSameJobInstanceWithoutDuplicates() throws Exception {
        jdbcTemplate.queryForObject("SELECT prepare_settlement_seed(?)", Long.class, 1001);
        List<Long> committedIds = new ArrayList<>();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        ItemWriter<SettlementContribution> writer = chunk -> {
            boolean containsFailurePoint = chunk.getItems().stream().anyMatch(item -> item.id() == 1001L);
            if (containsFailurePoint && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("OFFSET 재시작 검증을 위한 의도적 실패");
            }
            chunk.getItems().forEach(item -> committedIds.add(item.id()));
        };
        JpaPagingItemReader<SettlementItem> reader =
                new OffsetReaderFactory(entityManagerFactory).create("restartOffset");
        Step step = new StepBuilder("restartOffsetStep", jobRepository)
                .<SettlementItem, SettlementContribution>chunk(1000, transactionManager)
                .reader(reader).processor(new SettlementChecksumProcessor()).writer(writer).build();
        Job job = new JobBuilder("restartOffsetCorrectnessJob", jobRepository).start(step).build();
        JobParameters parameters = new JobParametersBuilder().addString("case", "offset-same-instance")
                .toJobParameters();

        assertThat(jobLauncher.run(job, parameters).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(jobLauncher.run(job, parameters).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(committedIds).containsExactlyElementsOf(expectedIds(1001)).doesNotHaveDuplicates();
    }

    /** 실제 JpaPagingItemReader를 고정 조건으로 만든다. */
    private ItemStreamReader<SettlementItem> offsetReader(String name) {
        return new OffsetReaderFactory(entityManagerFactory).create(name);
    }

    /** 실제 JPA keyset page source를 사용하는 Reader를 만든다. */
    private KeysetJpaItemReader keysetReader(String name) {
        return new KeysetJpaItemReader(name, ReaderConstants.PAGE_SIZE,
                new JpaKeysetPageSource(entityManager, ReaderConstants.READY_STATUS));
    }

    /** ItemStream Reader를 열고 끝까지 읽은 뒤 닫는다. */
    private List<SettlementItem> readAll(ItemStreamReader<SettlementItem> reader) throws Exception {
        List<SettlementItem> items = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            SettlementItem item;
            while ((item = reader.read()) != null) {
                items.add(item);
            }
        } finally {
            reader.close();
        }
        return items;
    }

    /** Entity 목록에서 검증할 ID 순서만 추출한다. */
    private List<Long> ids(List<SettlementItem> items) {
        return items.stream().map(SettlementItem::getId).toList();
    }

    /** 공통 Processor로 전체 checksum을 계산한다. */
    private long checksum(List<SettlementItem> items) throws Exception {
        SettlementChecksumProcessor processor = new SettlementChecksumProcessor();
        long checksum = 0L;
        for (SettlementItem item : items) {
            checksum += processor.process(item).checksum();
        }
        return checksum;
    }

    /** 1부터 주어진 수까지의 기대 ID 목록을 만든다. */
    private List<Long> expectedIds(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (long id = 1; id <= count; id++) {
            ids.add(id);
        }
        return ids;
    }
}
