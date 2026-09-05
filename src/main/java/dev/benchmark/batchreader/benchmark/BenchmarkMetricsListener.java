package dev.benchmark.batchreader.benchmark;

import dev.benchmark.batchreader.batch.ChecksumItemWriter;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

import java.time.Instant;

public class BenchmarkMetricsListener implements StepExecutionListener {

    private final BenchmarkRunResult parameters;
    private final ChecksumItemWriter writer;
    private final BenchmarkResultStore resultStore;
    private final long sampleIntervalMillis;
    private OldGenSampler sampler;
    private long startedNanos;
    private Instant startedAt;

    /** 실행 파라미터, 공통 Writer와 결과 저장소를 받아 listener를 만든다. */
    public BenchmarkMetricsListener(BenchmarkRunResult parameters, ChecksumItemWriter writer,
                                    BenchmarkResultStore resultStore, long sampleIntervalMillis) {
        this.parameters = parameters;
        this.writer = writer;
        this.resultStore = resultStore;
        this.sampleIntervalMillis = sampleIntervalMillis;
    }

    /** Step 시작 시각을 기록하고 Old Gen 샘플링을 시작한다. */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        startedAt = Instant.now();
        startedNanos = System.nanoTime();
        sampler = OldGenSampler.forG1(sampleIntervalMillis);
        sampler.start();
    }

    /** Step 종료 시 실제 지표를 저장하고 대상 건수가 다르면 실패 ExitStatus를 반환한다. */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long durationNs = System.nanoTime() - startedNanos;
        sampler.close();
        boolean countValid = stepExecution.getReadCount() == parameters.targetRows()
                && stepExecution.getWriteCount() == parameters.targetRows();
        String exitStatus = countValid ? stepExecution.getExitStatus().getExitCode() : "COUNT_MISMATCH";
        Long peak = sampler.peakBytes().isPresent() ? sampler.peakBytes().getAsLong() : null;
        var jobContext = stepExecution.getJobExecution().getExecutionContext();
        boolean indexVerified = jobContext.get(BenchmarkIndexPreparationListener.VERIFIED_KEY, Boolean.class);
        String indexDefinition = jobContext.getString(BenchmarkIndexPreparationListener.DEFINITION_KEY);
        BenchmarkRunResult result = new BenchmarkRunResult(
                parameters.runId(), startedAt, Instant.now(), parameters.readerType(), parameters.targetRows(),
                parameters.indexMode(), indexVerified, indexDefinition, parameters.repetition(),
                durationNs, durationNs / 1_000_000.0,
                stepExecution.getReadCount(), stepExecution.getWriteCount(), stepExecution.getCommitCount(),
                writer.getChecksum(), exitStatus, peak, sampler.measurementStatus(),
                parameters.gcLogPath(), countValid);
        resultStore.save(result);
        if (!countValid) {
            stepExecution.setStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED.addExitDescription("targetRows와 실제 처리 건수가 다릅니다.");
        }
        return stepExecution.getExitStatus();
    }
}
