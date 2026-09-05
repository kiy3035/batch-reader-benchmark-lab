package dev.benchmark.batchreader;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBatchTest
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class StageOneIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.15-alpine")
            .withDatabaseName("batch_benchmark")
            .withUsername("benchmark")
            .withPassword("benchmark_local");

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Job smokeJob;

    /** Testcontainers PostgreSQL 접속 정보를 Spring 설정에 연결한다. */
    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Flyway 스키마와 smoke seed가 실제 PostgreSQL에 정확히 반영됐는지 확인한다. */
    @Test
    void migratesSchemaAndLoadsSmokeSeed() {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM settlement_item", Integer.class);
        Integer ready = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_item WHERE status = 'READY'", Integer.class);

        assertThat(total).isEqualTo(5);
        assertThat(ready).isEqualTo(4);
    }

    /** 실제 JobRepository를 사용한 최소 Batch Job이 완료되는지 확인한다. */
    @Test
    void runsSmokeBatchJob() throws Exception {
        jobLauncherTestUtils.setJob(smokeJob);
        JobExecution execution = jobLauncherTestUtils.launchJob(
                new JobParametersBuilder().addLong("testRun", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).singleElement()
                .satisfies(step -> assertThat(step.getStepName()).isEqualTo("smokeStep"));
    }

    /** 대량 seed 기반 함수가 작은 입력에서도 정확한 수와 값을 결정적으로 만드는지 확인한다. */
    @Test
    @Transactional
    void preparesDeterministicDatasetInsidePostgresql() {
        Long firstInsertCount = jdbcTemplate.queryForObject(
                "SELECT prepare_settlement_seed(23)", Long.class);
        String firstSignature = jdbcTemplate.queryForObject(
                "SELECT md5(string_agg(id || ':' || status || ':' || amount, ',' ORDER BY id)) FROM settlement_item",
                String.class);

        Long secondInsertCount = jdbcTemplate.queryForObject(
                "SELECT prepare_settlement_seed(23)", Long.class);
        String secondSignature = jdbcTemplate.queryForObject(
                "SELECT md5(string_agg(id || ':' || status || ':' || amount, ',' ORDER BY id)) FROM settlement_item",
                String.class);
        Integer ready = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_item WHERE status = 'READY'", Integer.class);
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_item WHERE status = 'COMPLETED'", Integer.class);

        assertThat(firstInsertCount).isEqualTo(25L);
        assertThat(secondInsertCount).isEqualTo(25L);
        assertThat(secondSignature).isEqualTo(firstSignature);
        assertThat(ready).isEqualTo(23);
        assertThat(completed).isEqualTo(2);
    }
}
