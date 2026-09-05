package dev.benchmark.batchreader.reader;

import dev.benchmark.batchreader.domain.SettlementItem;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeysetJpaItemReaderTest {

    /** 빈 결과에서 조회를 한 번만 수행하고 즉시 종료하는지 확인한다. */
    @Test
    void returnsNullForEmptyResult() throws Exception {
        List<Long> cursors = new ArrayList<>();
        KeysetJpaItemReader reader = readerFor(List.of(), 3, cursors);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNull();
        assertThat(reader.read()).isNull();
        assertThat(cursors).containsExactly(0L);
    }

    /** ID gap과 마지막 부분 페이지가 있어도 모든 ID를 오름차순으로 읽는지 확인한다. */
    @Test
    void readsAcrossPagesWithoutAssumingContinuousIds() throws Exception {
        List<SettlementItem> items = items(2, 7, 11, 40, 41);
        List<Long> cursors = new ArrayList<>();
        KeysetJpaItemReader reader = readerFor(items, 2, cursors);
        reader.open(new ExecutionContext());

        assertThat(readIds(reader)).containsExactly(2L, 7L, 11L, 40L, 41L);
        assertThat(cursors).containsExactly(0L, 7L, 40L, 41L);
    }

    /** 저장한 lastId를 새 Reader가 복원해 다음 ID부터 재개하는지 확인한다. */
    @Test
    void resumesAfterSavedLastId() throws Exception {
        List<SettlementItem> items = items(1, 4, 9, 15);
        ExecutionContext context = new ExecutionContext();
        KeysetJpaItemReader first = readerFor(items, 2, new ArrayList<>());
        first.open(context);
        assertThat(first.read().getId()).isEqualTo(1L);
        assertThat(first.read().getId()).isEqualTo(4L);
        first.update(context);
        first.close();

        KeysetJpaItemReader restarted = readerFor(items, 2, new ArrayList<>());
        restarted.open(context);

        assertThat(readIds(restarted)).containsExactly(9L, 15L);
        assertThat(context.getLong("testKeyset.lastId")).isEqualTo(4L);
    }

    /** 메모리 목록으로 실제 WHERE id > lastId와 LIMIT 동작을 흉내 내는 Reader를 만든다. */
    private KeysetJpaItemReader readerFor(List<SettlementItem> items, int pageSize, List<Long> cursors) {
        return new KeysetJpaItemReader("testKeyset", pageSize, (lastId, limit) -> {
            cursors.add(lastId);
            return items.stream().filter(item -> item.getId() > lastId).limit(limit).toList();
        });
    }

    /** Reader를 끝까지 읽어 반환 ID만 수집한다. */
    private List<Long> readIds(KeysetJpaItemReader reader) throws Exception {
        List<Long> ids = new ArrayList<>();
        SettlementItem item;
        while ((item = reader.read()) != null) {
            ids.add(item.getId());
        }
        return ids;
    }

    /** 지정한 gap ID를 가진 합성 Entity 목록을 만든다. */
    private List<SettlementItem> items(long... ids) {
        List<SettlementItem> items = new ArrayList<>();
        for (long id : ids) {
            items.add(SettlementItem.synthetic(id, "READY", BigDecimal.valueOf(id, 2)));
        }
        return items;
    }
}
