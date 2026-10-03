Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:BenchmarkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $script:BenchmarkRoot '..'))
$script:RuntimeRoot = Join-Path $script:BenchmarkRoot '.runtime'
$script:StatePath = Join-Path $script:RuntimeRoot 'cluster-state.json'
$script:ResultsRoot = Join-Path $script:BenchmarkRoot 'results'

function Get-BenchmarkConfig {
    param([string]$Path = (Join-Path $script:BenchmarkRoot 'configs\standard.json'))
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Benchmark configuration does not exist: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Assert-DockerReady {
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker is required for the pinned k6 image, but the Docker engine is unavailable.'
    }
}

function Get-ClusterState {
    if (-not (Test-Path -LiteralPath $script:StatePath)) {
        throw 'No benchmark cluster state exists. Run start-cluster.ps1 first.'
    }
    return Get-Content -LiteralPath $script:StatePath -Raw | ConvertFrom-Json
}

function Save-ClusterState {
    param([Parameter(Mandatory)]$State)
    New-Item -ItemType Directory -Force -Path $script:RuntimeRoot | Out-Null
    $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $script:StatePath -Encoding UTF8
}

function Test-ClusterHealthy {
    param([Parameter(Mandatory)]$State)
    foreach ($node in $State.nodes) {
        try {
            $health = Invoke-RestMethod -Uri "http://localhost:$($node.port)/actuator/health" -TimeoutSec 3
            if ($health.status -ne 'UP') {
                return $false
            }
            $cluster = Invoke-RestMethod -Uri "http://localhost:$($node.port)/cluster" -TimeoutSec 3
            $unhealthyMembers = @($cluster.members | Where-Object { $_.status -ne 'HEALTHY' })
            if ($unhealthyMembers.Count -gt 0) {
                return $false
            }
        } catch {
            return $false
        }
    }
    return $true
}

function Wait-ClusterHealthy {
    param(
        [Parameter(Mandatory)]$State,
        [int]$TimeoutSeconds = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-ClusterHealthy -State $State) {
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Cluster did not become healthy within $TimeoutSeconds seconds."
}

function Assert-DatasetPlacement {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)][string]$KeyPrefix,
        [Parameter(Mandatory)][int]$DatasetSize
    )
    $sampleSize = [Math]::Min($DatasetSize, 300)
    $counts = @{}
    for ($index = 0; $index -lt $sampleSize; $index++) {
        $key = '{0}-{1:d7}' -f $KeyPrefix, $index
        $ownerResponse = Invoke-RestMethod `
            -Uri "http://localhost:$($State.nodes[0].port)/cluster/owner/$key" `
            -TimeoutSec 5
        $ownerId = [string]$ownerResponse.owner.id
        if (-not $counts.ContainsKey($ownerId)) { $counts[$ownerId] = 0 }
        $counts[$ownerId]++
    }
    if ($counts.Count -ne [int]$State.nodeCount) {
        throw "Dataset placement validation reached $($counts.Count) of $($State.nodeCount) configured primary owners."
    }

    $sampleKey = '{0}-{1:d7}' -f $KeyPrefix, 0
    $placement = Invoke-RestMethod `
        -Uri "http://localhost:$($State.nodes[0].port)/cluster/replicas/$sampleKey" `
        -TimeoutSec 5
    if ([int]$placement.replicationFactor -ne [int]$State.replicationFactor) {
        throw "Cluster reports RF=$($placement.replicationFactor), expected RF=$($State.replicationFactor)."
    }
    return ($counts.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '
}

function Assert-BenchmarkPortsFree {
    param([int[]]$Ports)
    $occupied = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in $Ports }
    if ($occupied) {
        $details = ($occupied | ForEach-Object { "$($_.LocalPort) (PID $($_.OwningProcess))" }) -join ', '
        throw "Benchmark ports are already occupied: $details"
    }
}

function Assert-SafeRuntimePath {
    param([Parameter(Mandatory)][string]$Path)
    $resolved = [IO.Path]::GetFullPath($Path)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $script:RuntimeRoot 'runs'))
    if (-not $resolved.StartsWith($allowedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing runtime operation outside benchmark run storage: $resolved"
    }
    return $resolved
}

function Invoke-K6Container {
    param(
        [Parameter(Mandatory)][string]$Image,
        [Parameter(Mandatory)][string]$Script,
        [Parameter(Mandatory)][hashtable]$Environment
    )
    Assert-DockerReady
    New-Item -ItemType Directory -Force -Path $script:ResultsRoot | Out-Null
    $arguments = @(
        'run', '--rm',
        '-v', "${script:BenchmarkRoot}:/benchmark"
    )
    foreach ($entry in $Environment.GetEnumerator() | Sort-Object Key) {
        $arguments += @('-e', "$($entry.Key)=$($entry.Value)")
    }
    $arguments += @($Image, 'run', "/benchmark/scripts/$Script")
    & docker @arguments | Out-Host
    $exitCode = $LASTEXITCODE
    return $exitCode
}

function Get-ContainerBaseUrls {
    param([Parameter(Mandatory)]$State)
    return (($State.nodes | ForEach-Object { "http://host.docker.internal:$($_.port)" }) -join ';')
}

function Get-JavaProcessSnapshot {
    param([Parameter(Mandatory)]$State)
    $snapshot = @()
    foreach ($node in $State.nodes) {
        $process = Get-Process -Id $node.pid -ErrorAction SilentlyContinue
        if ($process) {
            $snapshot += [pscustomobject]@{
                node = $node.id
                pid = $node.pid
                cpuSeconds = [double]$process.CPU
                workingSetBytes = [long]$process.WorkingSet64
            }
        }
    }
    return $snapshot
}

function Stop-TrackedNode {
    param([Parameter(Mandatory)]$Node)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $($Node.pid)" -ErrorAction SilentlyContinue
    if (-not $process) {
        return
    }
    if ($process.Name -notmatch '^java(\.exe)?$' -or $process.CommandLine -notlike '*shardkv-0.0.1-SNAPSHOT.jar*') {
        throw "Refusing to stop PID $($Node.pid); it is not the tracked ShardKV JVM."
    }
    Stop-Process -Id $Node.pid -Force
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Process -Id $Node.pid -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 200
    }
    if (Get-Process -Id $Node.pid -ErrorAction SilentlyContinue) {
        throw "Tracked ShardKV JVM PID $($Node.pid) did not stop within 20 seconds."
    }
}

function Start-TrackedNode {
    param(
        [Parameter(Mandatory)]$State,
        [Parameter(Mandatory)]$Node,
        [string]$LogSuffix = 'restart'
    )
    if (Get-NetTCPConnection -LocalPort $Node.port -State Listen -ErrorAction SilentlyContinue) {
        throw "Cannot start $($Node.id); port $($Node.port) is occupied."
    }
    $env:SERVER_PORT = [string]$Node.port
    $env:SHARDKV_NODE_ID = $Node.id
    $env:SHARDKV_NODE_HOST = 'localhost'
    $env:SHARDKV_DATA_DIR = $Node.dataDir
    $env:SHARDKV_CLUSTER_MEMBERS = $State.membership
    $env:SHARDKV_REPLICATION_FACTOR = [string]$State.replicationFactor
    $env:SHARDKV_DEFAULT_CONSISTENCY = $State.defaultConsistency
    $env:SHARDKV_HEARTBEAT_INTERVAL = $State.heartbeatInterval
    $env:SHARDKV_FAILURE_THRESHOLD = [string]$State.failureThreshold
    $env:SHARDKV_RECOVERY_THRESHOLD = [string]$State.recoveryThreshold
    $env:SHARDKV_QUERY_MAX_RESULTS = [string]$State.queryMaxResults
    $env:DEBUG = 'false'
    $env:LOGGING_LEVEL_ROOT = 'INFO'

    $stdout = Join-Path $Node.logDir "$($Node.id)-$LogSuffix.out.log"
    $stderr = Join-Path $Node.logDir "$($Node.id)-$LogSuffix.err.log"
    $process = Start-Process -FilePath 'java' `
        -ArgumentList @('-jar', $State.jarPath) `
        -WorkingDirectory $script:RepoRoot `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru
    $Node.pid = $process.Id
    return $process
}
