package dev.benchmark.batchreader.benchmark;

import dev.benchmark.batchreader.batch.ChecksumItemWriter;
import dev.benchmark.batchreader.batch.SettlementChecksumProcessor;
import dev.benchmark.batchreader.batch.SettlementContribution;
import dev.benchmark.batchreader.domain.SettlementItem;
import dev.benchmark.batchreader.reader.JpaKeysetPageSource;
import dev.benchmark.batchreader.reader.KeysetJpaItemReader;
import dev.benchmark.batchreader.reader.OffsetReaderFactory;
import dev.benchmark.batchreader.reader.ReaderConstants;
import dev.benchmark.batchreader.experiment.ExperimentIndexManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.time.Instant;

@Configuration
public class BenchmarkJobConfiguration {

    @PersistenceContext
    private EntityManager entityManager;

    /** 파라미터로 Reader를 선택하는 단일 benchmark Job을 만든다. */
    @Bean
    Job benchmarkJob(JobRepository jobRepository, Step benchmarkStep,
                     BenchmarkIndexPreparationListener indexPreparationListener) {
        return new JobBuilder("benchmarkJob", jobRepository)
                .listener(indexPreparationListener)
                .start(benchmarkStep)
                .build();
    }

    /** Step 측정 전에 인덱스 모드를 실제 DB에 적용하는 Job listener를 만든다. */
    @Bean
    BenchmarkIndexPreparationListener benchmarkIndexPreparationListener(ExperimentIndexManager indexManager) {
        return new BenchmarkIndexPreparationListener(indexManager);
    }

    /** 동일 chunk 크기와 공통 처리 흐름에 측정 listener를 붙인 Step을 만든다. */
    @Bean
    Step benchmarkStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                       @Qualifier("benchmarkReader") ItemStreamReader<SettlementItem> reader,
                       @Qualifier("benchmarkWriter") ChecksumItemWriter writer,
                       BenchmarkMetricsListener listener) {
        return new StepBuilder("benchmarkStep", jobRepository)
                .<SettlementItem, SettlementContribution>chunk(ReaderConstants.PAGE_SIZE, transactionManager)
                .reader(reader).processor(new SettlementChecksumProcessor()).writer(writer).listener(listener).build();
    }

    /** readerType과 status JobParameter로 OFFSET 또는 Keyset Reader를 만든다. */
    @Bean("benchmarkReader")
    @StepScope
    ItemStreamReader<SettlementItem> benchmarkReader(
            EntityManagerFactory entityManagerFactory,
            @Value("#{jobParameters['readerType']}") String readerType,
            @Value("#{jobParameters['status'] ?: 'READY'}") String status,
            @Value("#{jobParameters['runId']}") String runId) {
        ReaderType type = ReaderType.valueOf(readerType);
        String name = "benchmarkReader." + runId;
        if (type == ReaderType.OFFSET) {
            return new OffsetReaderFactory(entityManagerFactory).create(name, status);
        }
        return new KeysetJpaItemReader(name, ReaderConstants.PAGE_SIZE,
                new JpaKeysetPageSource(entityManager, status));
    }

    /** run마다 독립 count와 checksum 상태를 갖는 Writer를 만든다. */
    @Bean("benchmarkWriter")
    @StepScope
    ChecksumItemWriter benchmarkWriter(@Value("#{jobParameters['runId']}") String runId) {
        return new ChecksumItemWriter("benchmarkWriter." + runId);
    }

    /** 결과 디렉터리를 사용하는 raw/summary 저장소를 만든다. */
    @Bean
    BenchmarkResultStore benchmarkResultStore(
            @Value("${benchmark.results-directory:results}") String resultsDirectory) {
        return new BenchmarkResultStore(Path.of(resultsDirectory));
    }

    /** JobParameter와 샘플링 설정을 묶어 Step 측정 listener를 만든다. */
    @Bean
    @StepScope
    BenchmarkMetricsListener benchmarkMetricsListener(
            @Qualifier("benchmarkWriter") ChecksumItemWriter writer,
            BenchmarkResultStore resultStore,
            @Value("#{jobParameters['runId']}") String runId,
            @Value("#{jobParameters['readerType']}") String readerType,
            @Value("#{jobParameters['targetRows']}") Long targetRows,
            @Value("#{jobParameters['indexMode']}") String indexMode,
            @Value("#{jobParameters['repetition']}") Long repetition,
            @Value("#{jobParameters['gcLogPath'] ?: ''}") String gcLogPath,
            @Value("${benchmark.old-gen-sample-interval-ms:50}") long intervalMillis) {
        BenchmarkRunResult parameters = new BenchmarkRunResult(runId, Instant.EPOCH, Instant.EPOCH,
                ReaderType.valueOf(readerType), targetRows, IndexMode.valueOf(indexMode), false, "", repetition.intValue(),
                0L, 0.0, 0L, 0L, 0L, 0L, "", null, "", gcLogPath, false);
        return new BenchmarkMetricsListener(parameters, writer, resultStore, intervalMillis);
    }
}
