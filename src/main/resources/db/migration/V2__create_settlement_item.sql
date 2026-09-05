CREATE TABLE settlement_item (
    id BIGINT PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    customer_id BIGINT NOT NULL,
    amount NUMERIC(15, 2) NOT NULL,
    settled_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reference_no VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL
);

COMMENT ON TABLE settlement_item IS '성능 실험용 합성 정산 데이터';
COMMENT ON COLUMN settlement_item.id IS 'Reader 정렬 및 커서에 사용하는 불변 키';
