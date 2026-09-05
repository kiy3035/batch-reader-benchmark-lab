#!/usr/bin/env bash
set -euo pipefail

target_rows="${1:?usage: ./scripts/collect-explain.sh 100000|500000|1000000 OFF|ON [output-directory]}"
index_mode="${2:?index mode required}"
output_directory="${3:-results/explain}"

case "$target_rows" in 100000|500000|1000000) ;; *) echo "unsupported target rows" >&2; exit 2;; esac
case "$index_mode" in OFF|ON) ;; *) echo "index mode must be OFF or ON" >&2; exit 2;; esac

# 현재 seed 건수를 검증한 뒤 앞·중간·마지막 페이지의 실행계획을 수집한다.
java -jar build/libs/batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar \
  --spring.batch.job.enabled=false --explain.enabled=true \
  "--explain.target-rows=${target_rows}" "--explain.index-mode=${index_mode}" \
  --explain.status=READY "--explain.output-directory=${output_directory}"
