package dev.benchmark.batchreader.experiment;

import dev.benchmark.batchreader.benchmark.IndexMode;

public record ExperimentIndexState(
        IndexMode mode,
        boolean verified,
        String definition
) {
}
