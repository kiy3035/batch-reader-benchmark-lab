param(
    [string]$ResultsDirectory = "results"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$resultRoot = Join-Path $repositoryRoot $ResultsDirectory
$runFiles = @(Get-ChildItem -LiteralPath (Join-Path $resultRoot "runs") -Filter "*.json")
if ($runFiles.Count -ne 36) {
    throw "Expected 36 run JSON files, found $($runFiles.Count)."
}
$runs = @($runFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })

# run별 성공, 처리 건수, 인덱스, JVM/GC/EXPLAIN 연결을 검증한다.
foreach ($run in $runs) {
    if ($run.exitStatus -ne "COMPLETED" -or -not $run.countValid -or -not $run.indexVerified) {
        throw "Invalid run: $($run.runId)"
    }
    if ($run.readCount -ne $run.targetRows -or $run.writeCount -ne $run.targetRows) {
        throw "Count mismatch: $($run.runId)"
    }
    if ($run.oldGenMeasurement -ne "MEASURED" -or $null -eq $run.peakOldGenBytes) {
        throw "Old Gen measurement missing: $($run.runId)"
    }
    if (-not $run.jvmOptions.Contains("-XX:+UseG1GC") -or -not $run.jvmOptions.Contains("-Xms512m") `
            -or -not $run.jvmOptions.Contains("-Xmx512m")) {
        throw "JVM options missing: $($run.runId)"
    }
    $gcFile = Join-Path $repositoryRoot $run.gcLogPath
    if (-not (Test-Path -LiteralPath $gcFile) -or (Get-Item -LiteralPath $gcFile).Length -eq 0) {
        throw "GC log missing: $($run.runId)"
    }
    if (@(Get-ChildItem -Path (Join-Path $repositoryRoot $run.explainArtifactPath)).Count -ne 3) {
        throw "EXPLAIN artifacts missing: $($run.runId)"
    }
}

# 전체 matrix, 실행 순서와 scale별 checksum 일치를 검증한다.
$groups = @($runs | Group-Object -Property { "$($_.readerType)|$($_.targetRows)|$($_.indexMode)" })
if ($groups.Count -ne 12 -or @($groups | Where-Object Count -ne 3).Count -ne 0) {
    throw "Expected 12 groups with 3 successful runs each."
}
$actualOrders = @($runs.executionOrder | ForEach-Object { [int]$_ } | Sort-Object)
if (@(Compare-Object -ReferenceObject @(1..36) -DifferenceObject $actualOrders).Count -ne 0) {
    throw "Execution order must contain every number from 1 through 36."
}
$checksums = [ordered]@{}
foreach ($scale in @(100000, 500000, 1000000)) {
    $scaleChecksums = @($runs | Where-Object targetRows -eq $scale | Select-Object -ExpandProperty checksum -Unique)
    if ($scaleChecksums.Count -ne 1) {
        throw "Reader checksum mismatch at scale $scale."
    }
    $checksums["$scale"] = [int64]$scaleChecksums[0]
}

# CSV와 환경/EXPLAIN 파일이 JSON 원본의 수와 일치하는지 검증한다.
$rawRows = @(Import-Csv -LiteralPath (Join-Path $resultRoot "raw-runs.csv"))
$summaryRows = @(Import-Csv -LiteralPath (Join-Path $resultRoot "summary.csv"))
$explainFiles = @(Get-ChildItem -LiteralPath (Join-Path $resultRoot "explain") -Filter "*.json")
$gcFiles = @(Get-ChildItem -LiteralPath (Join-Path $resultRoot "gc") -Filter "*.log")
if ($rawRows.Count -ne 36 -or $summaryRows.Count -ne 12 -or $explainFiles.Count -ne 36 `
        -or $gcFiles.Count -ne 36 -or -not (Test-Path -LiteralPath (Join-Path $resultRoot "environment.json"))) {
    throw "Result artifact counts are incomplete."
}
if (@($summaryRows | Where-Object successfulRuns -ne "3").Count -ne 0) {
    throw "Every summary group must contain three successful runs."
}
if (@($summaryRows | Where-Object { $_.targetRows -eq "500000" -and -not $_.growthVs100k }).Count -ne 0 `
        -or @($summaryRows | Where-Object { $_.targetRows -eq "1000000" `
            -and (-not $_.growthVs100k -or -not $_.growthVs500k) }).Count -ne 0 `
        -or @($summaryRows | Where-Object { -not $_.offsetToKeysetRatio }).Count -ne 0) {
    throw "Summary comparison ratios are incomplete."
}

$validation = [ordered]@{
    validatedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    valid = $true
    measuredRuns = $runs.Count
    groups = $groups.Count
    runsPerGroup = 3
    rawRows = $rawRows.Count
    summaryRows = $summaryRows.Count
    explainFiles = $explainFiles.Count
    gcLogs = $gcFiles.Count
    executionOrderComplete = $true
    checksumsByTargetRows = $checksums
}
$validation | ConvertTo-Json -Depth 5 | Set-Content `
    -LiteralPath (Join-Path $resultRoot "validation.json") -Encoding utf8
Write-Output ($validation | ConvertTo-Json -Compress)
