param(
    [string]$ComposeProject = "batch-reader-benchmark-lab",
    [string]$ResultsDirectory = "results",
    [switch]$SkipBuild,
    [switch]$Resume
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location -LiteralPath $repositoryRoot
$resultRoot = Join-Path $repositoryRoot $ResultsDirectory
$runDirectory = Join-Path $resultRoot "runs"
$applicationLogDirectory = Join-Path $repositoryRoot "build\stage5-application-logs"
$warmupDirectory = "build/stage5-warmup"
New-Item -ItemType Directory -Force $runDirectory | Out-Null
New-Item -ItemType Directory -Force $applicationLogDirectory | Out-Null

$existingRuns = @(Get-ChildItem -LiteralPath $runDirectory -Filter "*.json")
if (-not $Resume -and $existingRuns.Count -gt 0) {
    throw "Official run files already exist. Use -Resume only after checking them."
}

# 측정할 동일 JAR을 한 번만 만들고 전체 실행에서 재사용한다.
if (-not $SkipBuild) {
    & (Join-Path $repositoryRoot "gradlew.bat") --no-daemon bootJar
    if ($LASTEXITCODE -ne 0) {
        throw "Boot JAR build failed."
    }
}
$jarPath = Join-Path $repositoryRoot "build\libs\batch-reader-benchmark-lab-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Boot JAR is missing: $jarPath"
}

# 반복별 Reader와 인덱스 순서를 교차해 36개 실행 계획을 고정한다.
$plan = [System.Collections.Generic.List[object]]::new()
$executionOrder = 0
foreach ($targetRows in @(100000, 500000, 1000000)) {
    foreach ($repetition in @(1, 2, 3)) {
        $indexOrder = if ($repetition -eq 2) { @("ON", "OFF") } else { @("OFF", "ON") }
        $readerOrder = if ($repetition -eq 2) { @("KEYSET", "OFFSET") } else { @("OFFSET", "KEYSET") }
        foreach ($indexMode in $indexOrder) {
            foreach ($readerType in $readerOrder) {
                $executionOrder++
                $runId = "m{0:D2}-{1}-{2}-{3}-r{4}" -f $executionOrder, $targetRows,
                    $indexMode.ToLowerInvariant(), $readerType.ToLowerInvariant(), $repetition
                $plan.Add([pscustomobject][ordered]@{
                    executionOrder = $executionOrder
                    targetRows = $targetRows
                    readerType = $readerType
                    indexMode = $indexMode
                    repetition = $repetition
                    runId = $runId
                })
            }
        }
    }
}

docker compose -p $ComposeProject up -d --wait postgres
if ($LASTEXITCODE -ne 0) {
    throw "PostgreSQL Compose startup failed."
}
$initializationLog = Join-Path $applicationLogDirectory "matrix-initialization.log"
$initializationRunId = "matrix-initialization-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
& java -jar $jarPath --spring.batch.job.name=smokeJob "run.id=$initializationRunId" *> $initializationLog
if ($LASTEXITCODE -ne 0) {
    throw "Flyway and Batch initialization failed."
}
Write-Output "DATABASE_INITIALIZED"
& (Join-Path $PSScriptRoot "write-environment.ps1") `
    -ComposeProject $ComposeProject -ResultsDirectory $ResultsDirectory
$plan | Export-Csv -LiteralPath (Join-Path $resultRoot "execution-order.csv") `
    -NoTypeInformation -Encoding utf8

foreach ($targetRows in @(100000, 500000, 1000000)) {
    Write-Output "SCALE_START targetRows=$targetRows"
    & (Join-Path $PSScriptRoot "seed-scale.ps1") -ReadyRows $targetRows -ComposeProject $ComposeProject

    # 측정과 같은 snapshot에서 인덱스 OFF/ON 실행계획을 먼저 수집한다.
    foreach ($indexMode in @("OFF", "ON")) {
        $explainLog = Join-Path $applicationLogDirectory "explain-$targetRows-$($indexMode.ToLowerInvariant()).log"
        & (Join-Path $PSScriptRoot "collect-explain.ps1") `
            -TargetRows $targetRows -IndexMode $indexMode -OutputDirectory "$ResultsDirectory/explain" `
            *> $explainLog
        Write-Output "EXPLAIN_COMPLETE targetRows=$targetRows indexMode=$indexMode"
    }

    # 각 Reader/index 조건을 한 번씩 실행하고 warm-up 결과는 build 아래에 분리한다.
    foreach ($warmup in @(
        @{ reader = "OFFSET"; index = "OFF" },
        @{ reader = "KEYSET"; index = "OFF" },
        @{ reader = "KEYSET"; index = "ON" },
        @{ reader = "OFFSET"; index = "ON" }
    )) {
        $warmupId = "warmup-$targetRows-$($warmup.index.ToLowerInvariant())-$($warmup.reader.ToLowerInvariant())"
        $warmupLog = Join-Path $applicationLogDirectory "$warmupId.log"
        & (Join-Path $PSScriptRoot "run-benchmark.ps1") `
            -ReaderType $warmup.reader -TargetRows $targetRows -IndexMode $warmup.index -Repetition 1 `
            -RunId $warmupId -ExecutionOrder 0 -ResultsDirectory "$warmupDirectory/$targetRows" `
            -ApplicationLogPath $warmupLog
        Write-Output "WARMUP_COMPLETE targetRows=$targetRows reader=$($warmup.reader) index=$($warmup.index)"
    }

    foreach ($item in @($plan | Where-Object targetRows -eq $targetRows)) {
        $runPath = Join-Path $runDirectory "$($item.runId).json"
        if (Test-Path -LiteralPath $runPath) {
            $existing = Get-Content -LiteralPath $runPath -Raw | ConvertFrom-Json
            if ($existing.exitStatus -ne "COMPLETED" -or -not $existing.countValid `
                    -or $existing.readCount -ne $targetRows) {
                throw "Existing run is invalid and cannot be skipped: $($item.runId)"
            }
            Write-Output "RUN_SKIP order=$($item.executionOrder) runId=$($item.runId)"
            continue
        }
        Write-Output "RUN_START order=$($item.executionOrder)/36 runId=$($item.runId)"
        $applicationLog = Join-Path $applicationLogDirectory "$($item.runId).log"
        $readerName = $item.readerType.ToLowerInvariant()
        $indexName = $item.indexMode.ToLowerInvariant()
        $explainPattern = "$ResultsDirectory/explain/$targetRows-$readerName-$indexName-*.json"
        & (Join-Path $PSScriptRoot "run-benchmark.ps1") `
            -ReaderType $item.readerType -TargetRows $targetRows -IndexMode $item.indexMode `
            -Repetition $item.repetition -RunId $item.runId -ExecutionOrder $item.executionOrder `
            -ResultsDirectory $ResultsDirectory -ExplainArtifactPath $explainPattern `
            -ApplicationLogPath $applicationLog
        $completed = Get-Content -LiteralPath $runPath -Raw | ConvertFrom-Json
        Write-Output ("RUN_COMPLETE order={0}/36 durationMs={1} peakOldGenBytes={2} checksum={3}" -f `
            $item.executionOrder, $completed.durationMs, $completed.peakOldGenBytes, $completed.checksum)
    }
    $completedScaleRuns = @(Get-ChildItem -LiteralPath $runDirectory -Filter "*-$targetRows-*.json").Count
    Write-Output "SCALE_COMPLETE targetRows=$targetRows measuredRuns=$completedScaleRuns"
}

& (Join-Path $PSScriptRoot "validate-results.ps1") -ResultsDirectory $ResultsDirectory
Write-Output "MATRIX_COMPLETE measuredRuns=36"
