package dev.benchmark.batchreader.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class BenchmarkResultStore {

    private static final String RAW_HEADER = "runId,startedAt,endedAt,readerType,targetRows,indexMode,indexVerified,indexDefinition,repetition,durationNs,durationMs,readCount,writeCount,commitCount,checksum,exitStatus,peakOldGenBytes,oldGenMeasurement,gcLogPath,countValid\n";
    private static final String SUMMARY_HEADER = "readerType,targetRows,indexMode,successfulRuns,meanDurationMs,minDurationMs,maxDurationMs,stddevDurationMs,meanPeakOldGenBytes,maxPeakOldGenBytes\n";

    private final Path resultsDirectory;
    private final ObjectMapper objectMapper;

    /** 결과 디렉터리와 Java time 지원 JSON mapper를 구성한다. */
    public BenchmarkResultStore(Path resultsDirectory) {
        this.resultsDirectory = resultsDirectory;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** run별 JSON과 raw CSV를 저장하고 성공 run만 사용해 summary CSV를 다시 만든다. */
    public synchronized void save(BenchmarkRunResult result) {
        try {
            Path runsDirectory = resultsDirectory.resolve("runs");
            Files.createDirectories(runsDirectory);
            Files.createDirectories(resultsDirectory.resolve("gc"));
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(runsDirectory.resolve(safeRunId(result.runId()) + ".json").toFile(), result);
            appendRawCsv(result);
            writeSummary(loadRuns(runsDirectory));
        } catch (IOException exception) {
            throw new IllegalStateException("벤치마크 결과 저장에 실패했습니다.", exception);
        }
    }

    /** run JSON을 모두 읽어 생성 시각 순서로 반환한다. */
    private List<BenchmarkRunResult> loadRuns(Path runsDirectory) throws IOException {
        try (var paths = Files.list(runsDirectory)) {
            return paths.filter(path -> path.toString().endsWith(".json"))
                    .map(this::readRun)
                    .sorted(Comparator.comparing(BenchmarkRunResult::startedAt))
                    .toList();
        }
    }

    /** 단일 JSON 결과를 record로 역직렬화한다. */
    private BenchmarkRunResult readRun(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), BenchmarkRunResult.class);
        } catch (IOException exception) {
            throw new IllegalStateException("run JSON을 읽지 못했습니다: " + path, exception);
        }
    }

    /** raw CSV가 없으면 header를 만들고 run 한 줄을 추가한다. */
    private void appendRawCsv(BenchmarkRunResult result) throws IOException {
        Path raw = resultsDirectory.resolve("raw-runs.csv");
        if (Files.notExists(raw)) {
            Files.writeString(raw, RAW_HEADER, StandardCharsets.UTF_8);
        }
        String peak = result.peakOldGenBytes() == null ? "" : result.peakOldGenBytes().toString();
        String row = String.join(",",
                result.runId(), result.startedAt().toString(), result.endedAt().toString(),
                result.readerType().name(), Long.toString(result.targetRows()), result.indexMode().name(),
                Boolean.toString(result.indexVerified()), quote(result.indexDefinition()),
                Integer.toString(result.repetition()), Long.toString(result.durationNs()),
                format(result.durationMs()), Long.toString(result.readCount()), Long.toString(result.writeCount()),
                Long.toString(result.commitCount()), Long.toString(result.checksum()), result.exitStatus(), peak,
                quote(result.oldGenMeasurement()), result.gcLogPath(), Boolean.toString(result.countValid())) + "\n";
        Files.writeString(raw, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Reader/scale/index별 성공 결과의 기본 통계를 summary CSV로 쓴다. */
    private void writeSummary(List<BenchmarkRunResult> runs) throws IOException {
        StringBuilder csv = new StringBuilder(SUMMARY_HEADER);
        runs.stream().filter(run -> run.countValid() && "COMPLETED".equals(run.exitStatus()))
                .collect(java.util.stream.Collectors.groupingBy(run ->
                        run.readerType() + "," + run.targetRows() + "," + run.indexMode()))
                .entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                    List<BenchmarkRunResult> group = entry.getValue();
                    var duration = group.stream().mapToDouble(BenchmarkRunResult::durationMs).summaryStatistics();
                    double mean = duration.getAverage();
                    double variance = group.stream().mapToDouble(run -> Math.pow(run.durationMs() - mean, 2)).average().orElse(0);
                    var peaks = group.stream().filter(run -> run.peakOldGenBytes() != null)
                            .mapToLong(BenchmarkRunResult::peakOldGenBytes).summaryStatistics();
                    csv.append(entry.getKey()).append(',').append(group.size()).append(',')
                            .append(format(mean)).append(',').append(format(duration.getMin())).append(',')
                            .append(format(duration.getMax())).append(',').append(format(Math.sqrt(variance))).append(',')
                            .append(peaks.getCount() == 0 ? "" : format(peaks.getAverage())).append(',')
                            .append(peaks.getCount() == 0 ? "" : peaks.getMax()).append('\n');
                });
        Files.writeString(resultsDirectory.resolve("summary.csv"), csv, StandardCharsets.UTF_8);
    }

    /** 파일명에 사용할 수 없는 runId 문자를 밑줄로 바꾼다. */
    private String safeRunId(String runId) {
        return runId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 소수점 출력 형식을 로케일과 무관하게 고정한다. */
    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** CSV 문자열의 큰따옴표를 이스케이프한다. */
    private String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
