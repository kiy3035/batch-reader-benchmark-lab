package dev.benchmark.batchreader.batch;

import dev.benchmark.batchreader.domain.SettlementItem;
import org.springframework.batch.item.ItemProcessor;

public class SettlementChecksumProcessor implements ItemProcessor<SettlementItem, SettlementContribution> {

    /** ID와 센트 단위 금액으로 Reader 공통의 가벼운 결정적 checksum 기여값을 계산한다. */
    @Override
    public SettlementContribution process(SettlementItem item) {
        long amountInCents = item.getAmount().movePointRight(2).longValueExact();
        return new SettlementContribution(item.getId(), item.getId() * 31L + amountInCents);
    }
}
