package dev.benchmark.batchreader.reader;

import dev.benchmark.batchreader.domain.SettlementItem;

import java.util.List;

@FunctionalInterface
public interface KeysetPageSource {

    /** 주어진 ID 다음의 행을 고유 ID 오름차순으로 제한된 수만큼 조회한다. */
    List<SettlementItem> fetchAfter(long lastId, int pageSize);
}
