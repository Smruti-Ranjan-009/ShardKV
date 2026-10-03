[CmdletBinding()]
param(
    [string]$ConfigPath,
    [switch]$StartObservability
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$config = if ($ConfigPath) { Get-BenchmarkConfig -Path $ConfigPath } else { Get-BenchmarkConfig }
$state = Get-ClusterState
if ($state.nodeCount -ne 3 -or $state.replicationFactor -ne 3) {
    throw 'Failure-under-load benchmark requires a 3-node RF=3 cluster.'
}
Wait-ClusterHealthy -State $state -TimeoutSeconds 60
$rawRoot = Join-Path $script:ResultsRoot 'raw'
$transientRoot = Join-Path $script:ResultsRoot 'transient'
New-Item -ItemType Directory -Force -Path $rawRoot, $transientRoot | Out-Null
$observabilityStarted = $false

function Get-PrometheusValue {
    param([string]$Query)
    try {
        $encoded = [Uri]::EscapeDataString($Query)
        $result = (Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$encoded" -TimeoutSec 5).data.result
        if ($result.Count -eq 0) { return $null }
        return [double]$result[0].value[1]
    } catch {
        return $null
    }
}

try {
    if ($StartObservability) {
        & docker compose -f (Join-Path $script:RepoRoot 'observability\docker-compose.yml') up -d | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Could not start Phase 7 observability services.' }
        $observabilityStarted = $true
        Start-Sleep -Seconds 10
    }

    $warmupEnvironment = @{
        BASE_URLS = "http://host.docker.internal:$($state.nodes[0].port);http://host.docker.internal:$($state.nodes[1].port)"
        WORKLOAD = 'get-heavy'
        CONSISTENCY = 'QUORUM'
        VUS = [string]$config.failureVus
        DURATION = "$($config.warmupSeconds)s"
        DURATION_SECONDS = [string]$config.warmupSeconds
        DATASET_SIZE = [string]$config.datasetSize
        PAYLOAD_BYTES = [string]$config.payloadBytes
        ERROR_RATE_LIMIT = [string]$config.healthyErrorRateLimit
        SUMMARY_PATH = '/benchmark/results/transient/failure-warmup.json'
        EXPERIMENT = 'warmup'
        NODE_COUNT = '3'
        REPLICATION_FACTOR = '3'
        RUN_NUMBER = '1'
        WARMUP_SECONDS = '0'
        K6_IMAGE = $config.k6Image
    }
    $warmupExit = Invoke-K6Container -Image $config.k6Image -Script 'workload.js' -Environment $warmupEnvironment
    if ($warmupExit -ne 0) { throw "Failure benchmark warm-up failed with exit code $warmupExit." }

    $fileName = 'failure-under-load-3n-rf3-quorum-all-run1.json'
    $summaryPath = Join-Path $rawRoot $fileName
    $environment = [ordered]@{
        BASE_URLS = "http://host.docker.internal:$($state.nodes[0].port);http://host.docker.internal:$($state.nodes[1].port)"
        VUS = [string]$config.failureVus
        DURATION = "$($config.failureDurationSeconds)s"
        DURATION_SECONDS = [string]$config.failureDurationSeconds
        DATASET_SIZE = [string]$config.datasetSize
        PAYLOAD_BYTES = [string]$config.payloadBytes
        SUMMARY_PATH = "/benchmark/results/raw/$fileName"
        RUN_NUMBER = '1'
        WARMUP_SECONDS = [string]$config.warmupSeconds
        K6_IMAGE = $config.k6Image
    }

    $dockerArguments = @('run', '--rm', '-v', "${script:BenchmarkRoot}:/benchmark")
    foreach ($entry in $environment.GetEnumerator()) {
        $dockerArguments += @('-e', "$($entry.Key)=$($entry.Value)")
    }
    $dockerArguments += @($config.k6Image, 'run', '/benchmark/scripts/failure-workload.js')
    $k6Stdout = Join-Path $state.runRoot 'failure-k6.out.log'
    $k6Stderr = Join-Path $state.runRoot 'failure-k6.err.log'
    $metricsBefore = [ordered]@{
        heartbeatFailures = Get-PrometheusValue 'sum(shardkv_heartbeat_total{result="failure"})'
        healthTransitions = Get-PrometheusValue 'sum(shardkv_node_health_transitions_total)'
        readFailovers = Get-PrometheusValue 'sum(shardkv_read_failover_total)'
    }
    $resourceBefore = Get-JavaProcessSnapshot -State $state
    $benchmarkStartedAt = (Get-Date).ToUniversalTime()
    $k6Process = Start-Process -FilePath 'docker' `
        -ArgumentList $dockerArguments `
        -WorkingDirectory $script:RepoRoot `
        -RedirectStandardOutput $k6Stdout `
        -RedirectStandardError $k6Stderr `
        -WindowStyle Hidden `
        -PassThru

    Start-Sleep -Seconds ([int]$config.failureStopAfterSeconds)
    $failedNode = $state.nodes[2]
    $nodeStoppedAt = (Get-Date).ToUniversalTime()
    Stop-TrackedNode -Node $failedNode

    $unhealthyObservedAt = $null
    $deadline = (Get-Date).AddSeconds(20)
    do {
        try {
            $cluster = Invoke-RestMethod "http://localhost:$($state.nodes[0].port)/cluster" -TimeoutSec 3
            $status = ($cluster.members | Where-Object { $_.id -eq $failedNode.id }).status
            if ($status -eq 'UNHEALTHY') {
                $unhealthyObservedAt = (Get-Date).ToUniversalTime()
                break
            }
        } catch {}
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    Start-Sleep -Seconds ([int]$config.failureRestartAfterSeconds)
    $nodeRestartedAt = (Get-Date).ToUniversalTime()
    Start-TrackedNode -State $state -Node $failedNode -LogSuffix 'failure-restart' | Out-Null
    Save-ClusterState -State $state

    $healthyObservedAt = $null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        if (Test-ClusterHealthy -State $state) {
            $healthyObservedAt = (Get-Date).ToUniversalTime()
            break
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $waitMilliseconds = ([int]$config.failureDurationSeconds + 60) * 1000
    if (-not $k6Process.WaitForExit($waitMilliseconds)) {
        Stop-Process -Id $k6Process.Id -Force -ErrorAction SilentlyContinue
        throw "k6 failure benchmark exceeded its $($config.failureDurationSeconds + 60)-second execution timeout."
    }
    $k6Process.WaitForExit()
    $k6Process.Refresh()
    $k6ExitCode = [int]$k6Process.ExitCode
    $benchmarkFinishedAt = (Get-Date).ToUniversalTime()
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        throw "Failure benchmark did not produce summary. Inspect $k6Stderr"
    }
    Start-Sleep -Seconds 7
    $metricsAfter = [ordered]@{
        heartbeatFailures = Get-PrometheusValue 'sum(shardkv_heartbeat_total{result="failure"})'
        healthTransitions = Get-PrometheusValue 'sum(shardkv_node_health_transitions_total)'
        readFailovers = Get-PrometheusValue 'sum(shardkv_read_failover_total)'
    }
    $resourceAfter = Get-JavaProcessSnapshot -State $state
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    $quorumAvailable = [double]$summary.consistencyBreakdown.quorum.errorRate -lt 0.01
    $allFailedDuringOutage = [double]$summary.consistencyBreakdown.all.errorRate -gt 0
    $transitionObserved = $null -ne $unhealthyObservedAt -and $null -ne $healthyObservedAt
    $summary | Add-Member -NotePropertyName failureInjection -NotePropertyValue ([pscustomobject]@{
        failedNode = $failedNode.id
        benchmarkStartedAt = $benchmarkStartedAt.ToString('o')
        nodeStoppedAt = $nodeStoppedAt.ToString('o')
        unhealthyObservedAt = if ($unhealthyObservedAt) { $unhealthyObservedAt.ToString('o') } else { $null }
        nodeRestartedAt = $nodeRestartedAt.ToString('o')
        healthyObservedAt = if ($healthyObservedAt) { $healthyObservedAt.ToString('o') } else { $null }
        benchmarkFinishedAt = $benchmarkFinishedAt.ToString('o')
        actualNodeDownSeconds = [Math]::Round(($nodeRestartedAt - $nodeStoppedAt).TotalSeconds, 3)
        unhealthyDetectionSeconds = if ($unhealthyObservedAt) { [Math]::Round(($unhealthyObservedAt - $nodeStoppedAt).TotalSeconds, 3) } else { $null }
        healthyRecoverySeconds = if ($healthyObservedAt) { [Math]::Round(($healthyObservedAt - $nodeRestartedAt).TotalSeconds, 3) } else { $null }
    })
    $summary | Add-Member -NotePropertyName prometheusCorrelation -NotePropertyValue ([pscustomobject]@{
        before = $metricsBefore
        after = $metricsAfter
    })
    $summary | Add-Member -NotePropertyName validation -NotePropertyValue ([pscustomobject]@{
        valid = $k6ExitCode -eq 0 -and $quorumAvailable -and $allFailedDuringOutage -and $transitionObserved
        k6ExitCode = $k6ExitCode
        quorumAvailable = $quorumAvailable
        allFailuresObserved = $allFailedDuringOutage
        healthTransitionsObserved = $transitionObserved
        clusterHealthyAfter = Test-ClusterHealthy -State $state
        resourceSamplesBefore = $resourceBefore
        resourceSamplesAfter = $resourceAfter
    })
    $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    Write-Output $summaryPath
} finally {
    if (Test-Path -LiteralPath $script:StatePath) {
        $currentState = Get-ClusterState
        $node3 = $currentState.nodes[2]
        if (-not (Get-Process -Id $node3.pid -ErrorAction SilentlyContinue)) {
            try {
                Start-TrackedNode -State $currentState -Node $node3 -LogSuffix 'failure-final-restart' | Out-Null
                Save-ClusterState -State $currentState
            } catch {
                Write-Warning "Could not restore failed node during cleanup: $($_.Exception.Message)"
            }
        }
    }
    if ($observabilityStarted) {
        & docker compose -f (Join-Path $script:RepoRoot 'observability\docker-compose.yml') down | Out-Host
    }
}
