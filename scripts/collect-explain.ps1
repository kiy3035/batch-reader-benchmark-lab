param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(100000, 500000, 1000000)]
    [long]$TargetRows,
    [Parameter(Mandatory = $true)]
    [ValidateSet("OFF", "ON")]
    [string]$IndexMode,
    [string]$OutputDirectory = "results/explain"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()

# 현재 seed 건수를 검증한 뒤 앞·중간·마지막 페이지의 실행계획을 수집한다.
& java `
    -jar "build/libs/batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar" `
    --spring.batch.job.enabled=false `
    --explain.enabled=true `
    "--explain.target-rows=$TargetRows" `
    "--explain.index-mode=$IndexMode" `
    --explain.status=READY `
    "--explain.output-directory=$OutputDirectory"

if ($LASTEXITCODE -ne 0) {
    throw "EXPLAIN collection failed with exit code $LASTEXITCODE"
}
