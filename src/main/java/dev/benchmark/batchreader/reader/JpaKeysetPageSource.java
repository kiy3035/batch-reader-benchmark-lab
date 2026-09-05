package dev.benchmark.batchreader.reader;

import dev.benchmark.batchreader.domain.SettlementItem;
import jakarta.persistence.EntityManager;

import java.util.List;

public class JpaKeysetPageSource implements KeysetPageSource {

    private final EntityManager entityManager;
    private final String status;

    /** JPA 조회에 사용할 EntityManager와 공통 상태 조건을 보관한다. */
    public JpaKeysetPageSource(EntityManager entityManager, String status) {
        this.entityManager = entityManager;
        this.status = status;
    }

    /** 이전 페이지 Entity를 분리하고 lastId 다음 페이지를 JPA 최대 결과 수로 제한해 조회한다. */
    @Override
    public List<SettlementItem> fetchAfter(long lastId, int pageSize) {
        entityManager.clear();
        return entityManager.createQuery("""
                        SELECT s
                        FROM SettlementItem s
                        WHERE s.status = :status AND s.id > :lastId
                        ORDER BY s.id ASC
                        """, SettlementItem.class)
                .setParameter("status", status)
                .setParameter("lastId", lastId)
                .setMaxResults(pageSize)
                .setHint("org.hibernate.readOnly", true)
                .getResultList();
    }
}
