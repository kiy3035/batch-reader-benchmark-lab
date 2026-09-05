param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("OFFSET", "KEYSET")]
    [string]$ReaderType,
    [Parameter(Mandatory = $true)]
    [ValidateSet(100000, 500000, 1000000)]
    [long]$TargetRows,
    [Parameter(Mandatory = $true)]
    [ValidateSet("OFF", "ON")]
    [string]$IndexMode,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 3)]
    [int]$Repetition,
    [string]$RunId = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$ReaderType-$TargetRows-$IndexMode-r$Repetition"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$gcDirectory = Join-Path $PSScriptRoot "..\results\gc"
New-Item -ItemType Directory -Force $gcDirectory | Out-Null
$gcLogPath = "results/gc/$RunId.log"

# 각 run을 고정 heap과 G1GC를 사용하는 독립 JVM에서 실행한다.
& java `
    -XX:+UseG1GC `
    -Xms512m `
    -Xmx512m `
    "-Xlog:gc*,safepoint:file=$gcLogPath`:time,uptime,level,tags" `
    -jar "build/libs/batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar" `
    --spring.batch.job.name=benchmarkJob `
    "readerType=$ReaderType" `
    "targetRows=$TargetRows" `
    "indexMode=$IndexMode" `
    "repetition=$Repetition" `
    "runId=$RunId" `
    "status=READY" `
    "gcLogPath=$gcLogPath"

if ($LASTEXITCODE -ne 0) {
    throw "Benchmark run failed with exit code $LASTEXITCODE"
}
