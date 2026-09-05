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
    [string]$RunId = "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$ReaderType-$TargetRows-$IndexMode-r$Repetition",
    [ValidateRange(0, 36)]
    [int]$ExecutionOrder = 0,
    [string]$ResultsDirectory = "results",
    [string]$ExplainArtifactPath = "",
    [string]$ApplicationLogPath = ""
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
if ([System.IO.Path]::IsPathRooted($ResultsDirectory)) {
    throw "ResultsDirectory must be relative to the repository root."
}
$normalizedResultsDirectory = $ResultsDirectory.Replace("\", "/").TrimEnd("/")
$gcDirectory = Join-Path $PSScriptRoot "..\$ResultsDirectory\gc"
New-Item -ItemType Directory -Force $gcDirectory | Out-Null
$gcLogPath = "$normalizedResultsDirectory/gc/$RunId.log"
$resolvedExplainPath = if ($ExplainArtifactPath) {
    $ExplainArtifactPath
} else {
    $readerName = $ReaderType.ToLowerInvariant()
    $indexName = $IndexMode.ToLowerInvariant()
    "results/explain/$TargetRows-$readerName-$indexName-*.json"
}
$jvmOptions = "-XX:+UseG1GC -Xms512m -Xmx512m"
$javaArguments = @(
    "-XX:+UseG1GC",
    "-Xms512m",
    "-Xmx512m",
    "-Xlog:gc*,safepoint:file=${gcLogPath}:time,uptime,level,tags",
    "-jar",
    "build/libs/batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar",
    "--spring.batch.job.name=benchmarkJob",
    "--benchmark.results-directory=$normalizedResultsDirectory",
    "readerType=$ReaderType",
    "targetRows=$TargetRows",
    "indexMode=$IndexMode",
    "repetition=$Repetition",
    "runId=$RunId",
    "executionOrder=$ExecutionOrder",
    "status=READY",
    "gcLogPath=$gcLogPath",
    "explainArtifactPath=$resolvedExplainPath",
    "jvmOptions=$jvmOptions"
)

# 각 run을 고정 heap과 G1GC를 사용하는 독립 JVM에서 실행한다.
if ($ApplicationLogPath) {
    $applicationLogDirectory = Split-Path -Parent $ApplicationLogPath
    if ($applicationLogDirectory) {
        New-Item -ItemType Directory -Force $applicationLogDirectory | Out-Null
    }
    & java @javaArguments *> $ApplicationLogPath
} else {
    & java @javaArguments
}

if ($LASTEXITCODE -ne 0) {
    throw "Benchmark run failed with exit code $LASTEXITCODE"
}
