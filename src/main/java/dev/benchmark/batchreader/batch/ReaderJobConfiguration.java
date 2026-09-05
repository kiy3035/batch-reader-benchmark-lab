package dev.benchmark.batchreader.batch;

import dev.benchmark.batchreader.domain.SettlementItem;
import dev.benchmark.batchreader.reader.JpaKeysetPageSource;
import dev.benchmark.batchreader.reader.KeysetJpaItemReader;
import dev.benchmark.batchreader.reader.OffsetReaderFactory;
import dev.benchmark.batchreader.reader.ReaderConstants;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ReaderJobConfiguration {

    @PersistenceContext
    private EntityManager entityManager;

    /** OFFSET Reader 전용 Job을 구성한다. */
    @Bean
    Job offsetReaderJob(JobRepository jobRepository, Step offsetReaderStep) {
        return new JobBuilder("offsetReaderJob", jobRepository).start(offsetReaderStep).build();
    }

    /** Keyset Reader 전용 Job을 구성한다. */
    @Bean
    Job keysetReaderJob(JobRepository jobRepository, Step keysetReaderStep) {
        return new JobBuilder("keysetReaderJob", jobRepository).start(keysetReaderStep).build();
    }

    /** pageSize와 chunkSize가 1000인 단일 스레드 OFFSET Step을 구성한다. */
    @Bean
    Step offsetReaderStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          EntityManagerFactory entityManagerFactory) {
        ItemReader<SettlementItem> reader = new OffsetReaderFactory(entityManagerFactory).create("offsetReader");
        return readerStep("offsetReaderStep", reader, new ChecksumItemWriter("offsetWriter"),
                jobRepository, transactionManager);
    }

    /** pageSize와 chunkSize가 1000인 단일 스레드 Keyset Step을 구성한다. */
    @Bean
    Step keysetReaderStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        ItemReader<SettlementItem> reader = new KeysetJpaItemReader("keysetReader", ReaderConstants.PAGE_SIZE,
                new JpaKeysetPageSource(entityManager, ReaderConstants.READY_STATUS));
        return readerStep("keysetReaderStep", reader, new ChecksumItemWriter("keysetWriter"),
                jobRepository, transactionManager);
    }

    /** 두 Reader에 동일한 Processor, Writer 계약과 chunk 크기를 적용한 Step을 만든다. */
    private Step readerStep(String name, ItemReader<SettlementItem> reader, ChecksumItemWriter writer,
                            JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder(name, jobRepository)
                .<SettlementItem, SettlementContribution>chunk(ReaderConstants.PAGE_SIZE, transactionManager)
                .reader(reader)
                .processor(new SettlementChecksumProcessor())
                .writer(writer)
                .build();
    }
}
