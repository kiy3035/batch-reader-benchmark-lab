package dev.benchmark.batchreader.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import dev.benchmark.batchreader.benchmark.IndexMode;
import dev.benchmark.batchreader.benchmark.ReaderType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExplainArtifact(
        Instant capturedAt,
        long targetRows,
        ReaderType readerType,
        IndexMode indexMode,
        boolean indexVerified,
        String indexDefinition,
        ExplainPosition position,
        long pageOffset,
        long correspondingLastId,
        String sql,
        Map<String, Object> bindings,
        double planningTimeMs,
        double executionTimeMs,
        List<ExplainPlanNode> planNodes,
        JsonNode rawExplain
) {
}
