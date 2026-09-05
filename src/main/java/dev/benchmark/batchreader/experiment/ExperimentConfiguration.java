package dev.benchmark.batchreader.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.benchmark.batchreader.benchmark.IndexMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class ExperimentConfiguration {

    /** 로컬 Docker 실험 DB 제한을 검사하는 guard를 만든다. */
    @Bean
    LocalExperimentDatabaseGuard localExperimentDatabaseGuard(DataSource dataSource) {
        return new LocalExperimentDatabaseGuard(dataSource);
    }

    /** 보조 인덱스 전환과 카탈로그 검증을 담당하는 manager를 만든다. */
    @Bean
    ExperimentIndexManager experimentIndexManager(JdbcTemplate jdbcTemplate,
                                                   LocalExperimentDatabaseGuard databaseGuard) {
        return new ExperimentIndexManager(jdbcTemplate, databaseGuard);
    }

    /** 실제 SQL 실행계획과 추출 지표를 저장하는 collector를 만든다. */
    @Bean
    ExplainCollector explainCollector(JdbcTemplate jdbcTemplate, LocalExperimentDatabaseGuard databaseGuard,
                                      ExperimentIndexManager indexManager) {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new ExplainCollector(jdbcTemplate, objectMapper, databaseGuard, indexManager);
    }

    /** 명시적으로 활성화했을 때만 인덱스를 준비하고 EXPLAIN 묶음을 수집한다. */
    @Bean
    @ConditionalOnProperty(name = "explain.enabled", havingValue = "true")
    ApplicationRunner explainRunner(
            ExperimentIndexManager indexManager,
            ExplainCollector collector,
            @Value("${explain.target-rows}") long targetRows,
            @Value("${explain.index-mode}") IndexMode indexMode,
            @Value("${explain.status:READY}") String status,
            @Value("${explain.output-directory:results/explain}") String outputDirectory) {
        return arguments -> {
            ExperimentIndexState indexState = indexManager.apply(indexMode);
            collector.collect(targetRows, status, indexState, Path.of(outputDirectory));
        };
    }
}
