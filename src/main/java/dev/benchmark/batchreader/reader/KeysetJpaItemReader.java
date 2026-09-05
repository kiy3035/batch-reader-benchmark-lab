package dev.benchmark.batchreader.reader;

import dev.benchmark.batchreader.domain.SettlementItem;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import java.util.Collections;
import java.util.List;

public class KeysetJpaItemReader implements ItemStreamReader<SettlementItem> {

    private static final long INITIAL_LAST_ID = 0L;

    private final String name;
    private final int pageSize;
    private final KeysetPageSource pageSource;
    private List<SettlementItem> page = Collections.emptyList();
    private int pageIndex;
    private long lastId = INITIAL_LAST_ID;
    private boolean exhausted;

    /** 고유 상태 이름, 페이지 크기와 페이지 조회 구현으로 Reader를 만든다. */
    public KeysetJpaItemReader(String name, int pageSize, KeysetPageSource pageSource) {
        this.name = name;
        this.pageSize = pageSize;
        this.pageSource = pageSource;
    }

    /** 현재 페이지에서 다음 항목을 반환하고 페이지가 비면 lastId 뒤의 다음 페이지를 조회한다. */
    @Override
    public SettlementItem read() {
        if (pageIndex >= page.size() && !loadNextPage()) {
            return null;
        }
        SettlementItem item = page.get(pageIndex++);
        lastId = item.getId();
        return item;
    }

    /** 재시작 시 마지막 커밋 ID를 복원하고 페이지 상태를 초기화한다. */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        lastId = executionContext.getLong(lastIdKey(), INITIAL_LAST_ID);
        page = Collections.emptyList();
        pageIndex = 0;
        exhausted = false;
    }

    /** 정상 반환한 마지막 ID를 Step ExecutionContext 체크포인트로 저장한다. */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        executionContext.putLong(lastIdKey(), lastId);
    }

    /** Reader가 보유한 현재 페이지 참조를 해제한다. */
    @Override
    public void close() throws ItemStreamException {
        page = Collections.emptyList();
        pageIndex = 0;
    }

    /** 다음 keyset 페이지를 읽고 더 이상 행이 없는지 판정한다. */
    private boolean loadNextPage() {
        if (exhausted) {
            return false;
        }
        page = pageSource.fetchAfter(lastId, pageSize);
        pageIndex = 0;
        exhausted = page.isEmpty();
        return !exhausted;
    }

    /** 여러 Reader의 상태가 충돌하지 않도록 이름이 포함된 체크포인트 키를 만든다. */
    private String lastIdKey() {
        return name + ".lastId";
    }
}
