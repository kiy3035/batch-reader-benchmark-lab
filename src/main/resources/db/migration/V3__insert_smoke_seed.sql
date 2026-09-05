INSERT INTO settlement_item (id, status, customer_id, amount, settled_at, reference_no, payload)
VALUES
    (1, 'READY', 101, 1000.00, TIMESTAMP '2024-01-01 00:00:01', 'SMOKE-000001', '{"source":"smoke","sequence":1}'),
    (2, 'READY', 102, 2000.00, TIMESTAMP '2024-01-01 00:00:02', 'SMOKE-000002', '{"source":"smoke","sequence":2}'),
    (4, 'COMPLETED', 104, 4000.00, TIMESTAMP '2024-01-01 00:00:04', 'SMOKE-000004', '{"source":"smoke","sequence":4}'),
    (7, 'READY', 107, 7000.00, TIMESTAMP '2024-01-01 00:00:07', 'SMOKE-000007', '{"source":"smoke","sequence":7}'),
    (11, 'READY', 111, 11000.00, TIMESTAMP '2024-01-01 00:00:11', 'SMOKE-000011', '{"source":"smoke","sequence":11}');
