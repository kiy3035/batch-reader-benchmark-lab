package dev.benchmark.batchreader.benchmark;

import java.time.Instant;

public record BenchmarkRunResult(
        String runId,
        Instant startedAt,
        Instant endedAt,
        ReaderType readerType,
        long targetRows,
        IndexMode indexMode,
        int repetition,
        long durationNs,
        double durationMs,
        long readCount,
        long writeCount,
        long commitCount,
        long checksum,
        String exitStatus,
        Long peakOldGenBytes,
        String oldGenMeasurement,
        String gcLogPath,
        boolean countValid
) {
}
