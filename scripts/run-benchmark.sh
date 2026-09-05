#!/usr/bin/env bash
set -euo pipefail

reader_type="${1:?usage: ./scripts/run-benchmark.sh OFFSET|KEYSET 100000|500000|1000000 OFF|ON 1|2|3 [run-id]}"
target_rows="${2:?target rows required}"
index_mode="${3:?index mode required}"
repetition="${4:?repetition required}"
run_id="${5:-$(date +%Y%m%d-%H%M%S)-${reader_type}-${target_rows}-${index_mode}-r${repetition}}"
gc_log_path="results/gc/${run_id}.log"
mkdir -p results/gc

case "$reader_type" in OFFSET|KEYSET) ;; *) echo "reader type must be OFFSET or KEYSET" >&2; exit 2;; esac
case "$target_rows" in 100000|500000|1000000) ;; *) echo "unsupported target rows" >&2; exit 2;; esac
case "$index_mode" in OFF|ON) ;; *) echo "index mode must be OFF or ON" >&2; exit 2;; esac
case "$repetition" in 1|2|3) ;; *) echo "repetition must be 1, 2, or 3" >&2; exit 2;; esac

# 각 run을 고정 heap과 G1GC를 사용하는 독립 JVM에서 실행한다.
java -XX:+UseG1GC -Xms512m -Xmx512m \
  "-Xlog:gc*,safepoint:file=${gc_log_path}:time,uptime,level,tags" \
  -jar build/libs/batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar \
  --spring.batch.job.name=benchmarkJob \
  "readerType=${reader_type}" "targetRows=${target_rows}" "indexMode=${index_mode}" \
  "repetition=${repetition}" "runId=${run_id}" "status=READY" "gcLogPath=${gc_log_path}"
