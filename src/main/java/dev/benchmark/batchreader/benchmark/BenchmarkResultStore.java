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
import java.util.Map;
import java.util.stream.Collectors;

public class BenchmarkResultStore {

    private static final String RAW_HEADER = "runId,executionOrder,startedAt,endedAt,readerType,targetRows,indexMode,indexVerified,indexDefinition,repetition,durationNs,durationMs,durationSeconds,readCount,writeCount,commitCount,checksum,exitStatus,peakOldGenBytes,oldGenMeasurement,gcLogPath,explainArtifactPath,jvmOptions,countValid\n";
    private static final String SUMMARY_HEADER = "readerType,targetRows,indexMode,successfulRuns,meanDurationMs,meanDurationSeconds,minDurationMs,maxDurationMs,stddevDurationMs,growthVs100k,growthVs500k,offsetToKeysetRatio,meanPeakOldGenBytes,maxPeakOldGenBytes\n";

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
                result.runId(), Integer.toString(result.executionOrder()), result.startedAt().toString(),
                result.endedAt().toString(),
                result.readerType().name(), Long.toString(result.targetRows()), result.indexMode().name(),
                Boolean.toString(result.indexVerified()), quote(result.indexDefinition()),
                Integer.toString(result.repetition()), Long.toString(result.durationNs()),
                format(result.durationMs()), format(result.durationSeconds()), Long.toString(result.readCount()),
                Long.toString(result.writeCount()),
                Long.toString(result.commitCount()), Long.toString(result.checksum()), result.exitStatus(), peak,
                quote(result.oldGenMeasurement()), quote(result.gcLogPath()), quote(result.explainArtifactPath()),
                quote(result.jvmOptions()), Boolean.toString(result.countValid())) + "\n";
        Files.writeString(raw, row, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Reader/scale/index별 성공 결과와 규모·Reader 비교 비율을 summary CSV로 쓴다. */
    private void writeSummary(List<BenchmarkRunResult> runs) throws IOException {
        Map<GroupKey, List<BenchmarkRunResult>> groups = runs.stream()
                .filter(this::isSuccessful)
                .collect(Collectors.groupingBy(run ->
                        new GroupKey(run.readerType(), run.targetRows(), run.indexMode())));
        Map<GroupKey, Double> means = groups.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().stream().mapToDouble(BenchmarkRunResult::durationMs).average().orElseThrow()));
        StringBuilder csv = new StringBuilder(SUMMARY_HEADER);
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                    GroupKey key = entry.getKey();
                    List<BenchmarkRunResult> group = entry.getValue();
                    var duration = group.stream().mapToDouble(BenchmarkRunResult::durationMs).summaryStatistics();
                    double mean = duration.getAverage();
                    double variance = group.stream().mapToDouble(run -> Math.pow(run.durationMs() - mean, 2)).average().orElse(0);
                    var peaks = group.stream().filter(run -> run.peakOldGenBytes() != null)
                            .mapToLong(BenchmarkRunResult::peakOldGenBytes).summaryStatistics();
                    csv.append(key.readerType()).append(',').append(key.targetRows()).append(',')
                            .append(key.indexMode()).append(',').append(group.size()).append(',')
                            .append(format(mean)).append(',').append(format(mean / 1_000.0)).append(',')
                            .append(format(duration.getMin())).append(',').append(format(duration.getMax())).append(',')
                            .append(format(Math.sqrt(variance))).append(',')
                            .append(scaleRatio(means, key, 100_000L)).append(',')
                            .append(scaleRatio(means, key, 500_000L)).append(',')
                            .append(readerRatio(means, key)).append(',')
                            .append(peaks.getCount() == 0 ? "" : format(peaks.getAverage())).append(',')
                            .append(peaks.getCount() == 0 ? "" : peaks.getMax()).append('\n');
                });
        Files.writeString(resultsDirectory.resolve("summary.csv"), csv, StandardCharsets.UTF_8);
    }

    /** 집계에 사용할 완료, 건수 일치, 인덱스 검증 run인지 확인한다. */
    private boolean isSuccessful(BenchmarkRunResult run) {
        return run.countValid() && run.indexVerified() && "COMPLETED".equals(run.exitStatus());
    }

    /** 현재 규모가 기준 규모보다 클 때 같은 Reader/index의 평균 증가 배율을 계산한다. */
    private String scaleRatio(Map<GroupKey, Double> means, GroupKey key, long baselineRows) {
        if (key.targetRows() <= baselineRows) {
            return "";
        }
        Double baseline = means.get(new GroupKey(key.readerType(), baselineRows, key.indexMode()));
        return baseline == null ? "" : format(means.get(key) / baseline);
    }

    /** 같은 scale/index에서 OFFSET 평균을 Keyset 평균으로 나눈 비율을 계산한다. */
    private String readerRatio(Map<GroupKey, Double> means, GroupKey key) {
        Double offset = means.get(new GroupKey(ReaderType.OFFSET, key.targetRows(), key.indexMode()));
        Double keyset = means.get(new GroupKey(ReaderType.KEYSET, key.targetRows(), key.indexMode()));
        return offset == null || keyset == null ? "" : format(offset / keyset);
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

    private record GroupKey(ReaderType readerType, long targetRows, IndexMode indexMode)
            implements Comparable<GroupKey> {

        /** Reader, 규모, 인덱스 순서로 summary 행을 안정적으로 정렬한다. */
        @Override
        public int compareTo(GroupKey other) {
            return Comparator.comparing((GroupKey key) -> key.readerType().name())
                    .thenComparingLong(GroupKey::targetRows)
                    .thenComparing(key -> key.indexMode().name())
                    .compare(this, other);
        }
    }
}
