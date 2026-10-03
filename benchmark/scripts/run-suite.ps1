[CmdletBinding()]
param(
    [ValidateSet('horizontal', 'replication', 'consistency', 'concurrency', 'query', 'failure')]
    [string[]]$Experiments = @('horizontal', 'replication', 'consistency', 'concurrency', 'query', 'failure'),
    [ValidateSet(1, 3, 5)][int[]]$HorizontalNodeCounts = @(1, 3, 5),
    [string]$ConfigPath
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$configFile = if ($ConfigPath) { [IO.Path]::GetFullPath($ConfigPath) } else { Join-Path $script:BenchmarkRoot 'configs\standard.json' }
$config = Get-BenchmarkConfig -Path $configFile

function Stop-CurrentCluster {
    if (Test-Path -LiteralPath $script:StatePath) {
        & (Join-Path $PSScriptRoot 'stop-cluster.ps1') -RemoveRunData | Out-Host
    }
}

function Start-Topology {
    param([int]$Nodes, [int]$ReplicationFactor, [string]$Consistency, [string]$RunId)
    Stop-CurrentCluster
    & (Join-Path $PSScriptRoot 'start-cluster.ps1') `
        -NodeCount $Nodes `
        -ReplicationFactor $ReplicationFactor `
        -DefaultConsistency $Consistency `
        -RunId $RunId `
        -BasePort ([int]$config.basePort) `
        -ResetData | Out-Null
}

function Preload-Topology {
    param([ValidateSet('kv', 'documents', 'both')][string]$Mode)
    & (Join-Path $PSScriptRoot 'preload.ps1') -Mode $Mode -ConfigPath $configFile | Out-Host
}

function Run-Scenario {
    param(
        [string]$Experiment,
        [string]$Workload,
        [string]$Consistency,
        [int]$Vus,
        [int]$Repetitions
    )
    & (Join-Path $PSScriptRoot 'run-benchmark.ps1') `
        -Experiment $Experiment `
        -Workload $Workload `
        -Consistency $Consistency `
        -Vus $Vus `
        -DurationSeconds ([int]$config.durationSeconds) `
        -WarmupSeconds ([int]$config.warmupSeconds) `
        -Repetitions $Repetitions `
        -ConfigPath $configFile | Out-Host
}

if (Test-Path -LiteralPath $script:StatePath) {
    throw 'A tracked benchmark cluster already exists. Stop it before starting the suite.'
}

& (Join-Path $PSScriptRoot 'capture-environment.ps1') -ConfigPath $configFile | Out-Host
try {
    if ('horizontal' -in $Experiments) {
        foreach ($nodes in $HorizontalNodeCounts) {
            Write-Output "Starting horizontal scale topology: $nodes node(s), RF=1"
            Start-Topology -Nodes $nodes -ReplicationFactor 1 -Consistency 'ALL' -RunId "horizontal-${nodes}n-rf1"
            Preload-Topology -Mode 'kv'
            foreach ($workload in 'get-heavy', 'put-heavy', 'mixed') {
                Run-Scenario -Experiment 'horizontal-scale' -Workload $workload -Consistency 'ALL' `
                    -Vus ([int]$config.headlineVus) -Repetitions ([int]$config.repetitions)
            }
            Stop-CurrentCluster
        }
    }

    if ('replication' -in $Experiments) {
        foreach ($replicationFactor in 1, 2, 3) {
            Write-Output "Starting replication-cost topology: 3 nodes, RF=$replicationFactor"
            Start-Topology -Nodes 3 -ReplicationFactor $replicationFactor -Consistency 'ALL' -RunId "replication-3n-rf$replicationFactor"
            Preload-Topology -Mode 'kv'
            Run-Scenario -Experiment 'replication-cost' -Workload 'put-heavy' -Consistency 'ALL' `
                -Vus ([int]$config.writeComparisonVus) -Repetitions ([int]$config.repetitions)
            Stop-CurrentCluster
        }
    }

    if ('consistency' -in $Experiments) {
        foreach ($consistency in 'ONE', 'QUORUM', 'ALL') {
            Write-Output "Starting consistency-cost topology: 3 nodes, RF=3, $consistency"
            Start-Topology -Nodes 3 -ReplicationFactor 3 -Consistency $consistency -RunId "consistency-3n-rf3-$($consistency.ToLowerInvariant())"
            Preload-Topology -Mode 'kv'
            Run-Scenario -Experiment 'consistency-cost' -Workload 'put-heavy' -Consistency $consistency `
                -Vus ([int]$config.writeComparisonVus) -Repetitions ([int]$config.repetitions)
            Stop-CurrentCluster
        }
    }

    if ('concurrency' -in $Experiments) {
        Write-Output 'Starting concurrency sweep topology: 3 nodes, RF=1'
        Start-Topology -Nodes 3 -ReplicationFactor 1 -Consistency 'ALL' -RunId 'concurrency-3n-rf1'
        Preload-Topology -Mode 'kv'
        foreach ($vus in $config.concurrencySweep) {
            Run-Scenario -Experiment 'concurrency-sweep' -Workload 'get-heavy' -Consistency 'ALL' `
                -Vus ([int]$vus) -Repetitions ([int]$config.concurrencySweepRepetitions)
        }
        Stop-CurrentCluster
    }

    if ('query' -in $Experiments) {
        Write-Output 'Starting distributed-query topology: 3 nodes, RF=3'
        Start-Topology -Nodes 3 -ReplicationFactor 3 -Consistency 'QUORUM' -RunId 'query-3n-rf3'
        Preload-Topology -Mode 'documents'
        foreach ($workload in 'single-filter', 'two-filter-and') {
            Run-Scenario -Experiment 'distributed-query' -Workload $workload -Consistency 'QUORUM' `
                -Vus ([int]$config.queryVus) -Repetitions ([int]$config.repetitions)
        }
        Stop-CurrentCluster
    }

    if ('failure' -in $Experiments) {
        Write-Output 'Starting failure-under-load topology: 3 nodes, RF=3'
        Start-Topology -Nodes 3 -ReplicationFactor 3 -Consistency 'QUORUM' -RunId 'failure-3n-rf3'
        Preload-Topology -Mode 'kv'
        & (Join-Path $PSScriptRoot 'run-failure-benchmark.ps1') -ConfigPath $configFile -StartObservability | Out-Host
        Stop-CurrentCluster
    }
} finally {
    Stop-CurrentCluster
}

& (Join-Path $PSScriptRoot 'summarize-results.ps1') | Out-Host
& (Join-Path $PSScriptRoot 'generate-charts.ps1') | Out-Host
Write-Output 'Benchmark suite complete.'
