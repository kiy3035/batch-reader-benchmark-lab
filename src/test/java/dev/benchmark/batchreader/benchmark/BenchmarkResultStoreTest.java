package dev.benchmark.batchreader.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkResultStoreTest {

    @TempDir
    Path tempDirectory;

    /** run JSON/raw CSV를 보존하고 성공한 유효 run만 summary에 집계하는지 확인한다. */
    @Test
    void writesRawAndSummaryArtifacts() throws Exception {
        BenchmarkResultStore store = new BenchmarkResultStore(tempDirectory);
        store.save(result("run-1", 10.0, 100L, "COMPLETED", true));
        store.save(result("run-2", 20.0, 200L, "COMPLETED", true));
        store.save(result("failed", 999.0, 999L, "FAILED", false));

        assertThat(Files.readAllLines(tempDirectory.resolve("raw-runs.csv"))).hasSize(4);
        try (var paths = Files.list(tempDirectory.resolve("runs"))) {
            assertThat(paths.filter(path -> path.toString().endsWith(".json")).count()).isEqualTo(3L);
        }
        assertThat(Files.readString(tempDirectory.resolve("summary.csv")))
                .contains("OFFSET,100000,OFF,2,15.000,0.015,10.000,20.000,5.000,,,")
                .doesNotContain("999.000");
    }

    /** 규모 증가 배율과 같은 조건의 OFFSET/Keyset 평균 비율을 계산하는지 확인한다. */
    @Test
    void calculatesScaleGrowthAndReaderRatio() throws Exception {
        BenchmarkResultStore store = new BenchmarkResultStore(tempDirectory);
        store.save(result("o-100", ReaderType.OFFSET, 100_000, 10.0));
        store.save(result("o-500", ReaderType.OFFSET, 500_000, 50.0));
        store.save(result("o-1000", ReaderType.OFFSET, 1_000_000, 120.0));
        store.save(result("k-100", ReaderType.KEYSET, 100_000, 5.0));
        store.save(result("k-500", ReaderType.KEYSET, 500_000, 20.0));
        store.save(result("k-1000", ReaderType.KEYSET, 1_000_000, 40.0));

        String summary = Files.readString(tempDirectory.resolve("summary.csv"));
        assertThat(summary)
                .contains("OFFSET,500000,OFF,1,50.000,0.050,50.000,50.000,0.000,5.000,,2.500")
                .contains("OFFSET,1000000,OFF,1,120.000,0.120,120.000,120.000,0.000,12.000,2.400,3.000")
                .contains("KEYSET,1000000,OFF,1,40.000,0.040,40.000,40.000,0.000,8.000,2.000,3.000");
    }

    /** 테스트할 최소 run 결과를 고정값으로 만든다. */
    private BenchmarkRunResult result(String runId, double durationMs, long peak,
                                      String exitStatus, boolean valid) {
        return new BenchmarkRunResult(runId, 1, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"), ReaderType.OFFSET, 100_000L, IndexMode.OFF,
                true, "ABSENT", 1, (long) (durationMs * 1_000_000), durationMs, durationMs / 1_000.0,
                100_000, 100_000, 100, 1234L, exitStatus, peak, "MEASURED",
                "results/gc/" + runId + ".log", "results/explain/*.json",
                "-XX:+UseG1GC -Xms512m -Xmx512m", valid);
    }

    /** 비율 집계에 사용할 성공 run을 Reader, 규모와 시간별로 만든다. */
    private BenchmarkRunResult result(String runId, ReaderType readerType, long targetRows, double durationMs) {
        return new BenchmarkRunResult(runId, 1, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"), readerType, targetRows, IndexMode.OFF,
                true, "ABSENT", 1, (long) (durationMs * 1_000_000), durationMs, durationMs / 1_000.0,
                targetRows, targetRows, targetRows / 1000, 1234L, "COMPLETED", 100L, "MEASURED",
                "results/gc/" + runId + ".log", "results/explain/*.json",
                "-XX:+UseG1GC -Xms512m -Xmx512m", true);
    }
}
