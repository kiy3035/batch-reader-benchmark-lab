package dev.benchmark.batchreader.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SmokeJobConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SmokeJobConfiguration.class);

    /** 1단계 기반 구성이 DB와 함께 동작하는지 확인하는 최소 Job을 만든다. */
    @Bean
    Job smokeJob(JobRepository jobRepository, Step smokeStep) {
        return new JobBuilder("smokeJob", jobRepository)
                .start(smokeStep)
                .build();
    }

    /** Flyway가 적재한 READY 행을 조회하고 예상 건수를 검증하는 Step을 만든다. */
    @Bean
    Step smokeStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate
    ) {
        return new StepBuilder("smokeStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    Long readyCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM settlement_item WHERE status = 'READY'",
                            Long.class
                    );
                    if (readyCount == null || readyCount == 0L) {
                        throw new IllegalStateException("처리할 READY 데이터가 없습니다.");
                    }
                    log.info("Smoke Batch Job completed: READY rows={}", readyCount);
                    return org.springframework.batch.repeat.RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }
}
