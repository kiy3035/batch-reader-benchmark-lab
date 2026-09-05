param(
    [ValidateSet(100000, 500000, 1000000)]
    [int]$ReadyRows,
    [string]$ComposeProject = "batch-reader-benchmark-lab"
)

$ErrorActionPreference = "Stop"
$OutputEncoding = [System.Text.UTF8Encoding]::new()
$databaseUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "benchmark" }
$databaseName = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "batch_benchmark" }

$sql = @"
SELECT prepare_settlement_seed($ReadyRows) AS inserted_rows;
SELECT status, COUNT(*) FROM settlement_item GROUP BY status ORDER BY status;
"@

$sql | docker compose -p $ComposeProject exec -T postgres psql `
    -v ON_ERROR_STOP=1 `
    -U $databaseUser `
    -d $databaseName

if ($LASTEXITCODE -ne 0) {
    throw "Deterministic seed failed with exit code $LASTEXITCODE"
}
