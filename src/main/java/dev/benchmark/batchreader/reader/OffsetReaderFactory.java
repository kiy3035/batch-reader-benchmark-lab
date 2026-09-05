package dev.benchmark.batchreader.reader;

import dev.benchmark.batchreader.domain.SettlementItem;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;

import java.util.Map;

public class OffsetReaderFactory {

    private final EntityManagerFactory entityManagerFactory;

    /** Reader마다 독립 EntityManager를 만들 팩토리를 보관한다. */
    public OffsetReaderFactory(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /** 고유 ID 오름차순으로 READY 행을 읽는 실제 JPA OFFSET Reader를 만든다. */
    public JpaPagingItemReader<SettlementItem> create(String name) {
        return create(name, ReaderConstants.READY_STATUS);
    }

    /** 지정한 상태의 행을 고유 ID 오름차순으로 읽는 실제 JPA OFFSET Reader를 만든다. */
    public JpaPagingItemReader<SettlementItem> create(String name, String status) {
        return new JpaPagingItemReaderBuilder<SettlementItem>()
                .name(name)
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT s FROM SettlementItem s WHERE s.status = :status ORDER BY s.id ASC")
                .parameterValues(Map.of("status", status))
                .pageSize(ReaderConstants.PAGE_SIZE)
                .saveState(true)
                .build();
    }
}
