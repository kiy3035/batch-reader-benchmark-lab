package dev.benchmark.batchreader.batch;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;

public class ChecksumItemWriter implements ItemStreamWriter<SettlementContribution> {

    private final String name;
    private long count;
    private long checksum;

    /** ExecutionContext에서 사용할 고유 상태 이름으로 Writer를 만든다. */
    public ChecksumItemWriter(String name) {
        this.name = name;
    }

    /** 전체 항목을 보관하지 않고 현재 chunk의 건수와 checksum만 누적한다. */
    @Override
    public void write(Chunk<? extends SettlementContribution> chunk) {
        for (SettlementContribution contribution : chunk) {
            count++;
            checksum += contribution.checksum();
        }
    }

    /** 재시작 시 커밋된 누적 건수와 checksum을 복원한다. */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        count = executionContext.getLong(name + ".count", 0L);
        checksum = executionContext.getLong(name + ".checksum", 0L);
    }

    /** 현재 누적 건수와 checksum을 커밋 단위 실행 상태에 저장한다. */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(name + ".count", count);
        executionContext.putLong(name + ".checksum", checksum);
    }

    /** 별도 외부 자원이 없으므로 종료 시 추가 작업을 하지 않는다. */
    @Override
    public void close() throws ItemStreamException {
        // 저장할 전체 항목이나 닫을 외부 자원이 없다.
    }
}
