#!/usr/bin/env bash
set -euo pipefail

ready_rows="${1:?usage: ./scripts/seed-scale.sh 100000|500000|1000000}"
case "$ready_rows" in
  100000|500000|1000000) ;;
  *) echo "ready_rows must be 100000, 500000, or 1000000" >&2; exit 2 ;;
esac

# READY 목표 건수와 10%의 비교 대상 외 행을 PostgreSQL 내부에서 결정적으로 생성한다.
docker compose exec -T postgres psql \
  -v ON_ERROR_STOP=1 \
  -U "${POSTGRES_USER:-benchmark}" \
  -d "${POSTGRES_DB:-batch_benchmark}" <<SQL
SELECT prepare_settlement_seed($ready_rows) AS inserted_rows;
SELECT status, COUNT(*) FROM settlement_item GROUP BY status ORDER BY status;
SQL
