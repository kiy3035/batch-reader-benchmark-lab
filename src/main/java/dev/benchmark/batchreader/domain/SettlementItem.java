package dev.benchmark.batchreader.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_item")
public class SettlementItem {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "settled_at", nullable = false)
    private LocalDateTime settledAt;

    @Column(name = "reference_no", nullable = false, length = 40)
    private String referenceNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** JPA가 Entity를 복원할 때 사용하는 기본 생성자다. */
    protected SettlementItem() {
    }

    /** 단위 테스트와 합성 데이터 검증에 사용할 정산 Entity를 만든다. */
    public static SettlementItem synthetic(long id, String status, BigDecimal amount) {
        SettlementItem item = new SettlementItem();
        item.id = id;
        item.status = status;
        item.customerId = 100_000L + id;
        item.amount = amount;
        item.settledAt = LocalDateTime.of(2024, 1, 1, 0, 0).plusSeconds(id);
        item.referenceNo = "TEST-" + id;
        item.payload = "{\"sequence\":" + id + "}";
        return item;
    }

    /** 정렬과 checksum 계산에 사용하는 고유 ID를 반환한다. */
    public Long getId() {
        return id;
    }

    /** 공통 조회 조건에 사용하는 정산 상태를 반환한다. */
    public String getStatus() {
        return status;
    }

    /** 합성 고객 식별자를 반환한다. */
    public Long getCustomerId() {
        return customerId;
    }

    /** checksum 계산에 사용하는 정산 금액을 반환한다. */
    public BigDecimal getAmount() {
        return amount;
    }

    /** 합성 정산 시각을 반환한다. */
    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    /** 합성 참조번호를 반환한다. */
    public String getReferenceNo() {
        return referenceNo;
    }

    /** 행 크기를 현실화하는 합성 payload를 반환한다. */
    public String getPayload() {
        return payload;
    }
}
