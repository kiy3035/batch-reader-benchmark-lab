param(
    [string]$ResultsDirectory = "results"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$resultRoot = Join-Path $repositoryRoot $ResultsDirectory
$reportRoot = Join-Path $resultRoot "report"
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

# CSV나 JSON 숫자를 현재 Windows 로케일과 무관하게 double로 변환한다.
function Convert-InvariantDouble([object]$value) {
    return [double]::Parse([string]$value, $invariantCulture)
}

# 보고서용 숫자를 고정 소수점 자릿수와 점 소수 구분자로 표현한다.
function Format-Invariant([double]$value, [int]$digits = 3) {
    return $value.ToString("F$digits", $invariantCulture)
}

# 객체 배열을 BOM 없는 UTF-8 CSV로 저장한다.
function Write-Utf8Csv([object[]]$rows, [string]$path) {
    $csv = @($rows | ConvertTo-Csv -NoTypeInformation)
    [System.IO.File]::WriteAllLines($path, $csv, [System.Text.UTF8Encoding]::new($false))
}

# unified GC 로그 한 개에서 JVM 전체 구간의 pause와 old region 지표를 읽는다.
function Read-GcMetrics([string]$path) {
    $pauseDurations = @()
    $youngNormalDurations = @()
    $fullGcCount = 0
    $maxOldRegions = 0
    $usesG1 = $false
    $heapMax512M = $false

    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match "Using G1$") {
            $usesG1 = $true
        }
        if ($line -match "Heap Max Capacity: 512M$") {
            $heapMax512M = $true
        }
        if ($line -match "\[gc\s*\].*GC\(\d+\) (?<kind>Pause .+?) (?<duration>[0-9.]+)ms$") {
            $duration = Convert-InvariantDouble $Matches.duration
            $pauseDurations += $duration
            if ($Matches.kind.StartsWith("Pause Young (Normal)")) {
                $youngNormalDurations += $duration
            }
            if ($Matches.kind.StartsWith("Pause Full")) {
                $fullGcCount++
            }
        }
        if ($line -match "Old regions: \d+->(?<after>\d+)") {
            $maxOldRegions = [Math]::Max($maxOldRegions, [int]$Matches.after)
        }
    }

    return [pscustomobject]@{
        usesG1 = $usesG1
        heapMax512M = $heapMax512M
        pauseCount = $pauseDurations.Count
        pauseTotalMs = ($pauseDurations | Measure-Object -Sum).Sum
        pauseMaxMs = ($pauseDurations | Measure-Object -Maximum).Maximum
        youngNormalPauseCount = $youngNormalDurations.Count
        youngNormalPauseTotalMs = ($youngNormalDurations | Measure-Object -Sum).Sum
        youngNormalPauseMaxMs = ($youngNormalDurations | Measure-Object -Maximum).Maximum
        fullGcCount = $fullGcCount
        maxOldRegions = $maxOldRegions
    }
}

if (-not (Test-Path -LiteralPath $reportRoot)) {
    New-Item -ItemType Directory -Path $reportRoot | Out-Null
}

$summaryRows = @(Import-Csv -LiteralPath (Join-Path $resultRoot "summary.csv"))
$runFiles = @(Get-ChildItem -LiteralPath (Join-Path $resultRoot "runs") -Filter "*.json")
$explainFiles = @(Get-ChildItem -LiteralPath (Join-Path $resultRoot "explain") -Filter "*.json")
if ($summaryRows.Count -ne 12 -or $runFiles.Count -ne 36 -or $explainFiles.Count -ne 36) {
    throw "Expected 12 summary rows, 36 run JSON files and 36 EXPLAIN files."
}

# duration과 Step Old Gen 값을 그래프 도구가 바로 읽을 수 있는 단위로 변환한다.
$durationRows = @($summaryRows | ForEach-Object {
    [pscustomobject]@{
        readerType = $_.readerType
        targetRows = $_.targetRows
        indexMode = $_.indexMode
        successfulRuns = $_.successfulRuns
        meanDurationSeconds = $_.meanDurationSeconds
        minDurationSeconds = Format-Invariant ((Convert-InvariantDouble $_.minDurationMs) / 1000.0)
        maxDurationSeconds = Format-Invariant ((Convert-InvariantDouble $_.maxDurationMs) / 1000.0)
        stddevDurationSeconds = Format-Invariant ((Convert-InvariantDouble $_.stddevDurationMs) / 1000.0)
        growthVs100k = $_.growthVs100k
        growthVs500k = $_.growthVs500k
        offsetToKeysetRatio = $_.offsetToKeysetRatio
        meanPeakOldGenMiB = Format-Invariant ((Convert-InvariantDouble $_.meanPeakOldGenBytes) / 1MB)
        maxPeakOldGenMiB = Format-Invariant ((Convert-InvariantDouble $_.maxPeakOldGenBytes) / 1MB)
    }
})
Write-Utf8Csv $durationRows (Join-Path $reportRoot "duration.csv")

# 각 EXPLAIN의 실제 scan node와 페이지 비용을 한 행으로 평탄화한다.
$explainRows = @($explainFiles | ForEach-Object {
    $artifact = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
    $scan = $artifact.planNodes | Where-Object { $_.relationName -eq "settlement_item" } | Select-Object -First 1
    if ($null -eq $scan) {
        throw "Settlement scan node is missing: $($_.FullName)"
    }
    $positionOrder = switch ($artifact.position) { "FIRST" { 1 } "MIDDLE" { 2 } "LAST" { 3 } }
    [pscustomobject]@{
        targetRows = $artifact.targetRows
        readerType = $artifact.readerType
        indexMode = $artifact.indexMode
        position = $artifact.position
        positionOrder = $positionOrder
        pageOffset = $artifact.pageOffset
        correspondingLastId = $artifact.correspondingLastId
        planningTimeMs = Format-Invariant (Convert-InvariantDouble $artifact.planningTimeMs)
        executionTimeMs = Format-Invariant (Convert-InvariantDouble $artifact.executionTimeMs)
        scanNodeType = $scan.nodeType
        selectedIndex = $scan.indexName
        scanActualRows = $scan.actualRows
        returnedRows = $artifact.planNodes[0].actualRows
        examinedPerReturnedRow = Format-Invariant ([double]$scan.actualRows / [double]$artifact.planNodes[0].actualRows)
        rowsRemovedByFilter = $scan.rowsRemovedByFilter
        sharedHitBlocks = $scan.sharedHitBlocks
        sharedReadBlocks = $scan.sharedReadBlocks
    }
} | Sort-Object targetRows, indexMode, readerType, positionOrder)
Write-Utf8Csv $explainRows (Join-Path $reportRoot "explain.csv")

# run JSON과 대응 GC 로그를 연결해 JVM 전체 구간 GC 원본 요약을 만든다.
$gcRunRows = @($runFiles | ForEach-Object {
    $run = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
    $gcPath = Join-Path $repositoryRoot $run.gcLogPath
    if (-not (Test-Path -LiteralPath $gcPath)) {
        throw "GC log is missing: $gcPath"
    }
    $gc = Read-GcMetrics $gcPath
    if (-not $gc.usesG1 -or -not $gc.heapMax512M) {
        throw "G1 or 512M heap evidence is missing: $gcPath"
    }
    [pscustomobject]@{
        runId = $run.runId
        executionOrder = $run.executionOrder
        readerType = $run.readerType
        targetRows = $run.targetRows
        indexMode = $run.indexMode
        repetition = $run.repetition
        stepDurationSeconds = Format-Invariant (Convert-InvariantDouble $run.durationSeconds)
        stepPeakOldGenBytes = $run.peakOldGenBytes
        stepPeakOldGenMiB = Format-Invariant ([double]$run.peakOldGenBytes / 1MB)
        processPauseCount = $gc.pauseCount
        processPauseTotalMs = Format-Invariant ([double]$gc.pauseTotalMs)
        processPauseMaxMs = Format-Invariant ([double]$gc.pauseMaxMs)
        processYoungNormalPauseCount = $gc.youngNormalPauseCount
        processYoungNormalPauseTotalMs = Format-Invariant ([double]$gc.youngNormalPauseTotalMs)
        processYoungNormalPauseMaxMs = Format-Invariant ([double]$gc.youngNormalPauseMaxMs)
        processFullGcCount = $gc.fullGcCount
        processMaxOldRegions = $gc.maxOldRegions
    }
} | Sort-Object executionOrder)
Write-Utf8Csv $gcRunRows (Join-Path $reportRoot "gc-runs.csv")

# 세 번의 GC run 지표를 Reader, 규모와 인덱스 조건별로 요약한다.
$gcSummaryRows = @($gcRunRows | Group-Object readerType, targetRows, indexMode | ForEach-Object {
    $group = $_.Group
    [pscustomobject]@{
        readerType = $group[0].readerType
        targetRows = $group[0].targetRows
        indexMode = $group[0].indexMode
        runs = $group.Count
        meanProcessPauseCount = Format-Invariant (($group.processPauseCount | Measure-Object -Average).Average)
        meanProcessPauseTotalMs = Format-Invariant (($group.processPauseTotalMs | Measure-Object -Average).Average)
        maxProcessPauseMs = Format-Invariant (($group.processPauseMaxMs | Measure-Object -Maximum).Maximum)
        meanYoungNormalPauseCount = Format-Invariant (($group.processYoungNormalPauseCount | Measure-Object -Average).Average)
        meanYoungNormalPauseTotalMs = Format-Invariant (($group.processYoungNormalPauseTotalMs | Measure-Object -Average).Average)
        totalFullGcCount = ($group.processFullGcCount | Measure-Object -Sum).Sum
        maxOldRegions = ($group.processMaxOldRegions | Measure-Object -Maximum).Maximum
        meanStepPeakOldGenMiB = Format-Invariant (($group.stepPeakOldGenMiB | Measure-Object -Average).Average)
        maxStepPeakOldGenMiB = Format-Invariant (($group.stepPeakOldGenMiB | Measure-Object -Maximum).Maximum)
    }
} | Sort-Object targetRows, indexMode, readerType)
Write-Utf8Csv $gcSummaryRows (Join-Path $reportRoot "gc-summary.csv")

Write-Output ("Report data generated: duration={0}, explain={1}, gcRuns={2}, gcSummary={3}" -f `
    $durationRows.Count, $explainRows.Count, $gcRunRows.Count, $gcSummaryRows.Count)
