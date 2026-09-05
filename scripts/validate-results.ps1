param(
    [string]$ResultsDirectory = "results"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$resultRoot = Join-Path $repositoryRoot $ResultsDirectory
$invariantCulture = [System.Globalization.CultureInfo]::InvariantCulture

# CSV 숫자를 로케일과 무관하게 double로 변환한다.
function Convert-InvariantDouble([string]$value) {
    return [double]::Parse($value, $invariantCulture)
}

# summary 반올림 오차 범위 안에서 실제값과 재계산값이 같은지 확인한다.
function Assert-Close([string]$name, [double]$actual, [double]$expected) {
    if ([Math]::Abs($actual - $expected) -gt 0.001) {
        throw "$name mismatch: actual=$actual expected=$expected"
    }
}
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

# run JSON에서 통계를 독립 재계산해 summary CSV와 대조한다.
$meanByGroup = @{}
foreach ($group in $groups) {
    $meanByGroup[$group.Name] = ($group.Group.durationMs | Measure-Object -Average).Average
}
foreach ($summary in $summaryRows) {
    $key = "$($summary.readerType)|$($summary.targetRows)|$($summary.indexMode)"
    $groupRuns = @($runs | Where-Object {
        $_.readerType -eq $summary.readerType -and $_.targetRows -eq [long]$summary.targetRows `
            -and $_.indexMode -eq $summary.indexMode
    })
    $durationStatistics = $groupRuns.durationMs | Measure-Object -Average -Minimum -Maximum
    $mean = [double]$durationStatistics.Average
    $variance = ($groupRuns | ForEach-Object { [Math]::Pow([double]$_.durationMs - $mean, 2) } `
        | Measure-Object -Average).Average
    Assert-Close "$key meanDurationMs" (Convert-InvariantDouble $summary.meanDurationMs) $mean
    Assert-Close "$key meanDurationSeconds" (Convert-InvariantDouble $summary.meanDurationSeconds) ($mean / 1000.0)
    Assert-Close "$key minDurationMs" (Convert-InvariantDouble $summary.minDurationMs) $durationStatistics.Minimum
    Assert-Close "$key maxDurationMs" (Convert-InvariantDouble $summary.maxDurationMs) $durationStatistics.Maximum
    Assert-Close "$key stddevDurationMs" (Convert-InvariantDouble $summary.stddevDurationMs) ([Math]::Sqrt($variance))

    $targetRows = [long]$summary.targetRows
    if ($targetRows -gt 100000) {
        $baselineKey = "$($summary.readerType)|100000|$($summary.indexMode)"
        Assert-Close "$key growthVs100k" (Convert-InvariantDouble $summary.growthVs100k) `
            ($mean / [double]$meanByGroup[$baselineKey])
    }
    if ($targetRows -gt 500000) {
        $baselineKey = "$($summary.readerType)|500000|$($summary.indexMode)"
        Assert-Close "$key growthVs500k" (Convert-InvariantDouble $summary.growthVs500k) `
            ($mean / [double]$meanByGroup[$baselineKey])
    }
    $offsetKey = "OFFSET|$targetRows|$($summary.indexMode)"
    $keysetKey = "KEYSET|$targetRows|$($summary.indexMode)"
    Assert-Close "$key offsetToKeysetRatio" (Convert-InvariantDouble $summary.offsetToKeysetRatio) `
        ([double]$meanByGroup[$offsetKey] / [double]$meanByGroup[$keysetKey])

    $peaks = $groupRuns.peakOldGenBytes | Measure-Object -Average -Maximum
    Assert-Close "$key meanPeakOldGenBytes" (Convert-InvariantDouble $summary.meanPeakOldGenBytes) $peaks.Average
    if ([long]$summary.maxPeakOldGenBytes -ne [long]$peaks.Maximum) {
        throw "$key maxPeakOldGenBytes mismatch."
    }
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
    summaryRecalculated = $true
    checksumsByTargetRows = $checksums
}
$validation | ConvertTo-Json -Depth 5 | Set-Content `
    -LiteralPath (Join-Path $resultRoot "validation.json") -Encoding utf8
Write-Output ($validation | ConvertTo-Json -Compress)
