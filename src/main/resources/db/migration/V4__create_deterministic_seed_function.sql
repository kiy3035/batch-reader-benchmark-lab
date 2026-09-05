CREATE OR REPLACE FUNCTION prepare_settlement_seed(ready_rows INTEGER)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
    completed_rows INTEGER;
    inserted_rows BIGINT;
BEGIN
    IF ready_rows < 0 OR ready_rows > 1000000 THEN
        RAISE EXCEPTION 'ready_rows must be between 0 and 1000000: %', ready_rows;
    END IF;

    completed_rows := ready_rows / 10;
    TRUNCATE TABLE settlement_item;

    INSERT INTO settlement_item (id, status, customer_id, amount, settled_at, reference_no, payload)
    SELECT
        series_id,
        CASE WHEN series_id <= ready_rows THEN 'READY' ELSE 'COMPLETED' END,
        100000 + (series_id % 10000),
        ((series_id * 37) % 1000000)::numeric / 100,
        TIMESTAMP '2024-01-01 00:00:00' + series_id * INTERVAL '1 second',
        'SETTLEMENT-' || lpad(series_id::text, 12, '0'),
        jsonb_build_object('source', 'deterministic-seed-v1', 'sequence', series_id)
    FROM generate_series(1, ready_rows + completed_rows) AS series_id;

    GET DIAGNOSTICS inserted_rows = ROW_COUNT;
    RETURN inserted_rows;
END;
$$;

COMMENT ON FUNCTION prepare_settlement_seed(INTEGER)
    IS '지정한 READY 건수와 10%의 COMPLETED 행을 동일한 규칙으로 다시 생성한다';
