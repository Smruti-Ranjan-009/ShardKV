[CmdletBinding()]
param(
    [ValidateSet(1, 3, 5)][int]$NodeCount,
    [ValidateRange(1, 5)][int]$ReplicationFactor,
    [ValidateSet('ONE', 'QUORUM', 'ALL')][string]$DefaultConsistency = 'ALL',
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9._-]+$')][string]$RunId,
    [int]$BasePort = 8081,
    [switch]$ResetData
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if ($ReplicationFactor -gt $NodeCount) {
    throw 'Replication factor cannot exceed node count.'
}
if (Test-Path -LiteralPath $script:StatePath) {
    throw 'A tracked benchmark cluster already exists. Run stop-cluster.ps1 first.'
}

$jarPath = Join-Path $script:RepoRoot 'target\shardkv-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Packaged application not found: $jarPath"
}

$ports = @($BasePort..($BasePort + $NodeCount - 1))
Assert-BenchmarkPortsFree -Ports $ports
$runRoot = Assert-SafeRuntimePath -Path (Join-Path $script:RuntimeRoot "runs\$RunId")
if ($ResetData -and (Test-Path -LiteralPath $runRoot)) {
    Remove-Item -LiteralPath $runRoot -Recurse -Force
}
$dataRoot = Join-Path $runRoot 'data'
$logRoot = Join-Path $runRoot 'logs'
New-Item -ItemType Directory -Force -Path $dataRoot, $logRoot | Out-Null

$membershipParts = for ($i = 1; $i -le $NodeCount; $i++) {
    "node-$i,localhost,$($BasePort + $i - 1)"
}
$membership = $membershipParts -join ';'
$nodes = @()
for ($i = 1; $i -le $NodeCount; $i++) {
    $nodeData = Join-Path $dataRoot "node-$i"
    $nodeLog = Join-Path $logRoot "node-$i"
    New-Item -ItemType Directory -Force -Path $nodeData, $nodeLog | Out-Null
    $nodes += [pscustomobject]@{
        id = "node-$i"
        host = 'localhost'
        port = $BasePort + $i - 1
        dataDir = $nodeData
        logDir = $nodeLog
        pid = 0
    }
}

$state = [pscustomobject]@{
    runId = $RunId
    runRoot = $runRoot
    jarPath = [IO.Path]::GetFullPath($jarPath)
    startedAt = (Get-Date).ToUniversalTime().ToString('o')
    nodeCount = $NodeCount
    replicationFactor = $ReplicationFactor
    defaultConsistency = $DefaultConsistency
    membership = $membership
    heartbeatInterval = '1s'
    failureThreshold = 3
    recoveryThreshold = 2
    nodes = $nodes
}

try {
    foreach ($node in $state.nodes) {
        Start-TrackedNode -State $state -Node $node -LogSuffix 'startup' | Out-Null
    }
    Save-ClusterState -State $state
    Wait-ClusterHealthy -State $state -TimeoutSeconds 90
    $state | ConvertTo-Json -Depth 8
} catch {
    foreach ($node in $state.nodes) {
        if ($node.pid -gt 0) {
            Stop-TrackedNode -Node $node
        }
    }
    Remove-Item -LiteralPath $script:StatePath -Force -ErrorAction SilentlyContinue
    throw
}
