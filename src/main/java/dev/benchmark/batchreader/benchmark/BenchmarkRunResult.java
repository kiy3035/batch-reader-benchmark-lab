package dev.benchmark.batchreader.benchmark;

import java.time.Instant;

public record BenchmarkRunResult(
        String runId,
        int executionOrder,
        Instant startedAt,
        Instant endedAt,
        ReaderType readerType,
        long targetRows,
        IndexMode indexMode,
        boolean indexVerified,
        String indexDefinition,
        int repetition,
        long durationNs,
        double durationMs,
        double durationSeconds,
        long readCount,
        long writeCount,
        long commitCount,
        long checksum,
        String exitStatus,
        Long peakOldGenBytes,
        String oldGenMeasurement,
        String gcLogPath,
        String explainArtifactPath,
        String jvmOptions,
        boolean countValid
) {
}
