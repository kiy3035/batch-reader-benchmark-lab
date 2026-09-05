package dev.benchmark.batchreader.experiment;

import java.util.List;

public record ExplainPlanNode(
        String nodeType,
        String relationName,
        String indexName,
        long planRows,
        long actualRows,
        long actualLoops,
        long rowsRemovedByFilter,
        long rowsRemovedByIndexRecheck,
        long sharedHitBlocks,
        long sharedReadBlocks,
        List<String> sortKey,
        String sortMethod
) {
}
