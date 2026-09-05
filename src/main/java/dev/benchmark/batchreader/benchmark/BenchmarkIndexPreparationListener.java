package dev.benchmark.batchreader.benchmark;

import dev.benchmark.batchreader.experiment.ExperimentIndexManager;
import dev.benchmark.batchreader.experiment.ExperimentIndexState;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

public class BenchmarkIndexPreparationListener implements JobExecutionListener {

    public static final String VERIFIED_KEY = "benchmark.indexVerified";
    public static final String DEFINITION_KEY = "benchmark.indexDefinition";

    private final ExperimentIndexManager indexManager;

    /** benchmark 실행 전에 사용할 인덱스 manager를 저장한다. */
    public BenchmarkIndexPreparationListener(ExperimentIndexManager indexManager) {
        this.indexManager = indexManager;
    }

    /** Step 타이머 시작 전에 요청 인덱스 모드를 적용하고 검증 결과를 Job 상태에 저장한다. */
    @Override
    public void beforeJob(JobExecution jobExecution) {
        String requested = jobExecution.getJobParameters().getString("indexMode");
        ExperimentIndexState state = indexManager.apply(IndexMode.valueOf(requested));
        jobExecution.getExecutionContext().put(VERIFIED_KEY, state.verified());
        jobExecution.getExecutionContext().putString(DEFINITION_KEY,
                state.definition() == null ? "ABSENT" : state.definition());
    }
}
