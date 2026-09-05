param(
    [string]$ComposeProject = "batch-reader-benchmark-lab",
    [string]$ResultsDirectory = "results"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$outputDirectory = Join-Path $repositoryRoot $ResultsDirectory
New-Item -ItemType Directory -Force $outputDirectory | Out-Null

# 운영체제와 CPU/RAM 정보를 읽기 전용으로 수집한다.
$operatingSystem = Get-CimInstance Win32_OperatingSystem
$processor = Get-CimInstance Win32_Processor | Select-Object -First 1
$javaVersion = (& java -version 2>&1 | Out-String).Trim()
$gradleVersionOutput = (& (Join-Path $repositoryRoot "gradlew.bat") --version | Out-String)
$gradleVersion = [regex]::Match($gradleVersionOutput, "Gradle\s+([0-9.]+)").Groups[1].Value
$dockerContext = (& docker context show).Trim()
$dockerVersion = (& docker version --format "{{.Server.Version}}").Trim()
$composeVersion = (& docker compose version --short).Trim()
$dockerInfo = ((& docker info --format "{{.NCPU}}|{{.MemTotal}}|{{.OperatingSystem}}|{{.Architecture}}") -join "").Trim() -split "\|"
$postgresVersion = ((& docker compose -p $ComposeProject exec -T postgres `
    psql -U $(if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "benchmark" }) `
    -d $(if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "batch_benchmark" }) `
    -At -c "SHOW server_version;") -join "").Trim()
$gitCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$workingTreeClean = -not [bool]((& git -C $repositoryRoot status --porcelain) -join "")

# 측정 통제 변수와 실제 도구 버전을 JSON 원본으로 저장한다.
$environment = [ordered]@{
    capturedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    timezone = [TimeZoneInfo]::Local.Id
    os = [ordered]@{
        caption = $operatingSystem.Caption
        version = $operatingSystem.Version
        buildNumber = $operatingSystem.BuildNumber
        architecture = $operatingSystem.OSArchitecture
    }
    cpu = [ordered]@{
        model = $processor.Name.Trim()
        logicalProcessors = [int]$processor.NumberOfLogicalProcessors
    }
    hostRamBytes = [int64]$operatingSystem.TotalVisibleMemorySize * 1KB
    java = $javaVersion
    gradle = $gradleVersion
    springBoot = "3.5.16"
    springBatch = "5.2.6"
    hibernate = "6.6.53.Final"
    postgresql = $postgresVersion
    docker = [ordered]@{
        context = $dockerContext
        engineVersion = $dockerVersion
        composeVersion = $composeVersion
        operatingSystem = $dockerInfo[2]
        architecture = $dockerInfo[3]
        cpus = [int]$dockerInfo[0]
        memoryBytes = [int64]$dockerInfo[1]
    }
    source = [ordered]@{
        gitCommit = $gitCommit
        workingTreeCleanBeforeMeasurement = $workingTreeClean
    }
    measurement = [ordered]@{
        readers = @("OFFSET", "KEYSET")
        targetRows = @(100000, 500000, 1000000)
        indexModes = @("OFF", "ON")
        repetitions = 3
        measuredRuns = 36
        chunkSize = 1000
        pageSize = 1000
        status = "READY"
        processorWriter = "동일 checksum processor와 in-memory aggregate writer"
        threading = "single-threaded step"
        jvmOptions = @("-XX:+UseG1GC", "-Xms512m", "-Xmx512m")
        gcLogging = "-Xlog:gc*,safepoint:file=<run-file>:time,uptime,level,tags"
        oldGenPool = "G1 Old Gen"
        oldGenSampleIntervalMs = 50
        cachePolicy = "명시적 조건별 warm-up 뒤의 warm-cache 비교이며 OS page cache를 강제로 비우지 않음"
        runOrder = "반복 1·3은 OFFSET→KEYSET, 반복 2는 KEYSET→OFFSET; index 순서는 OFF→ON, ON→OFF, OFF→ON으로 교차"
    }
}

$environment | ConvertTo-Json -Depth 8 | Set-Content `
    -LiteralPath (Join-Path $outputDirectory "environment.json") -Encoding utf8
Write-Output "Environment written: $ResultsDirectory/environment.json"
