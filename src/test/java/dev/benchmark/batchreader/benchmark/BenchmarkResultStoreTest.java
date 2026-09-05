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
                .contains("OFFSET,100000,OFF,2,15.000,10.000,20.000,5.000,150.000,200")
                .doesNotContain("999.000");
    }

    /** 테스트할 최소 run 결과를 고정값으로 만든다. */
    private BenchmarkRunResult result(String runId, double durationMs, long peak,
                                      String exitStatus, boolean valid) {
        return new BenchmarkRunResult(runId, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"), ReaderType.OFFSET, 100_000L, IndexMode.OFF,
                true, "ABSENT", 1, (long) (durationMs * 1_000_000), durationMs, 100_000, 100_000, 100,
                1234L, exitStatus, peak, "MEASURED", "results/gc/" + runId + ".log", valid);
    }
}
