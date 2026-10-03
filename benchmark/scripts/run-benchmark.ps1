[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9._-]+$')][string]$Experiment,
    [Parameter(Mandatory)][ValidateSet('get-heavy', 'put-heavy', 'mixed', 'single-filter', 'two-filter-and')][string]$Workload,
    [ValidateSet('ONE', 'QUORUM', 'ALL')][string]$Consistency = 'ALL',
    [ValidateRange(1, 1000)][int]$Vus,
    [ValidateRange(1, 3600)][int]$DurationSeconds,
    [ValidateRange(0, 600)][int]$WarmupSeconds,
    [ValidateRange(1, 100)][int]$Repetitions,
    [string]$ConfigPath
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$config = if ($ConfigPath) { Get-BenchmarkConfig -Path $ConfigPath } else { Get-BenchmarkConfig }
$state = Get-ClusterState
if (-not $PSBoundParameters.ContainsKey('Vus')) { $Vus = [int]$config.headlineVus }
if (-not $PSBoundParameters.ContainsKey('DurationSeconds')) { $DurationSeconds = [int]$config.durationSeconds }
if (-not $PSBoundParameters.ContainsKey('WarmupSeconds')) { $WarmupSeconds = [int]$config.warmupSeconds }
if (-not $PSBoundParameters.ContainsKey('Repetitions')) { $Repetitions = [int]$config.repetitions }

$isQuery = $Workload -in @('single-filter', 'two-filter-and')
$scriptName = if ($isQuery) { 'query-workload.js' } else { 'workload.js' }
$baseUrls = Get-ContainerBaseUrls -State $state
$rawRoot = Join-Path $script:ResultsRoot 'raw'
$transientRoot = Join-Path $script:ResultsRoot 'transient'
New-Item -ItemType Directory -Force -Path $rawRoot, $transientRoot | Out-Null

for ($runNumber = 1; $runNumber -le $Repetitions; $runNumber++) {
    Wait-ClusterHealthy -State $state -TimeoutSeconds 60
    if ($WarmupSeconds -gt 0) {
        $warmupEnvironment = @{
            BASE_URLS = $baseUrls
            WORKLOAD = if ($isQuery) { 'mixed' } else { $Workload }
            QUERY_TYPE = if ($isQuery) { $Workload } else { 'single-filter' }
            CONSISTENCY = $Consistency
            VUS = [string]$Vus
            DURATION = "${WarmupSeconds}s"
            DURATION_SECONDS = [string]$WarmupSeconds
            DATASET_SIZE = [string]$config.datasetSize
            PAYLOAD_BYTES = [string]$config.payloadBytes
            ERROR_RATE_LIMIT = [string]$config.healthyErrorRateLimit
            SUMMARY_PATH = '/benchmark/results/transient/warmup.json'
            EXPERIMENT = 'warmup'
            NODE_COUNT = [string]$state.nodeCount
            REPLICATION_FACTOR = [string]$state.replicationFactor
            RUN_NUMBER = [string]$runNumber
            WARMUP_SECONDS = '0'
            K6_IMAGE = $config.k6Image
        }
        $warmupExit = Invoke-K6Container -Image $config.k6Image -Script $scriptName -Environment $warmupEnvironment
        if ($warmupExit -ne 0) {
            throw "Warm-up failed for $Experiment/$Workload run $runNumber with k6 exit code $warmupExit."
        }
        Wait-ClusterHealthy -State $state -TimeoutSeconds 60
    }

    $safeConsistency = if ($isQuery) { 'na' } else { $Consistency.ToLowerInvariant() }
    $fileName = "$Experiment-$($state.nodeCount)n-rf$($state.replicationFactor)-$Workload-$safeConsistency-$($Vus)vu-run$runNumber.json"
    $hostSummaryPath = Join-Path $rawRoot $fileName
    $containerSummaryPath = "/benchmark/results/raw/$fileName"
    $environment = @{
        BASE_URLS = $baseUrls
        WORKLOAD = if ($isQuery) { 'mixed' } else { $Workload }
        QUERY_TYPE = if ($isQuery) { $Workload } else { 'single-filter' }
        CONSISTENCY = $Consistency
        VUS = [string]$Vus
        DURATION = "${DurationSeconds}s"
        DURATION_SECONDS = [string]$DurationSeconds
        DATASET_SIZE = [string]$config.datasetSize
        PAYLOAD_BYTES = [string]$config.payloadBytes
        ERROR_RATE_LIMIT = [string]$config.healthyErrorRateLimit
        SUMMARY_PATH = $containerSummaryPath
        EXPERIMENT = $Experiment
        NODE_COUNT = [string]$state.nodeCount
        REPLICATION_FACTOR = [string]$state.replicationFactor
        RUN_NUMBER = [string]$runNumber
        WARMUP_SECONDS = [string]$WarmupSeconds
        K6_IMAGE = $config.k6Image
    }

    $healthyBefore = Test-ClusterHealthy -State $state
    $resourceBefore = Get-JavaProcessSnapshot -State $state
    $startedAt = (Get-Date).ToUniversalTime().ToString('o')
    $exitCode = Invoke-K6Container -Image $config.k6Image -Script $scriptName -Environment $environment
    $finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    $resourceAfter = Get-JavaProcessSnapshot -State $state
    $healthyAfter = Test-ClusterHealthy -State $state

    if (-not (Test-Path -LiteralPath $hostSummaryPath)) {
        throw "k6 did not produce expected summary: $hostSummaryPath"
    }
    $summary = Get-Content -LiteralPath $hostSummaryPath -Raw | ConvertFrom-Json
    $cpuDelta = 0.0
    foreach ($after in $resourceAfter) {
        $before = $resourceBefore | Where-Object { $_.pid -eq $after.pid } | Select-Object -First 1
        if ($before) {
            $cpuDelta += [Math]::Max(0.0, ([double]$after.cpuSeconds - [double]$before.cpuSeconds))
        }
    }
    $workingSetAfter = ($resourceAfter | Measure-Object -Property workingSetBytes -Sum).Sum
    $valid = $exitCode -eq 0 -and $healthyBefore -and $healthyAfter -and
        [double]$summary.metrics.errorRate -lt [double]$config.healthyErrorRateLimit
    $summary | Add-Member -NotePropertyName execution -NotePropertyValue ([pscustomobject]@{
        startedAt = $startedAt
        finishedAt = $finishedAt
        k6ExitCode = $exitCode
        clusterHealthyBefore = $healthyBefore
        clusterHealthyAfter = $healthyAfter
        javaCpuSecondsDuringRun = [Math]::Round($cpuDelta, 3)
        javaWorkingSetBytesAfter = [long]$workingSetAfter
    })
    $summary | Add-Member -NotePropertyName validation -NotePropertyValue ([pscustomobject]@{
        valid = $valid
        healthyErrorRateLimit = [double]$config.healthyErrorRateLimit
        reason = if ($valid) { $null } else { 'k6 threshold, error-rate, or cluster-health validation failed' }
    })
    $summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $hostSummaryPath -Encoding UTF8

    $status = if ($valid) { 'VALID' } else { 'INVALID' }
    Write-Output ("{0}: {1} {2} run {3}; {4:N2} ops/s, p95 {5:N2} ms, errors {6:P4}" -f `
        $status, $Experiment, $Workload, $runNumber,
        [double]$summary.metrics.throughputOpsPerSecond,
        [double]$summary.metrics.latencyMs.p95,
        [double]$summary.metrics.errorRate)
}
