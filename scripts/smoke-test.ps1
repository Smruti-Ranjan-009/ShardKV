[CmdletBinding()]
param(
    [string]$ComposeFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'docker-compose.yml'),
    [string]$ProjectName = 'shardkv',
    [string]$GrafanaUser = 'admin',
    [string]$GrafanaPassword = 'admin',
    [switch]$VerifyPersistence,
    [switch]$VerifyFailureRecovery
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$nodes = @(
    [pscustomobject]@{ id = 'node-1'; service = 'shardkv-node-1'; url = 'http://localhost:8081' },
    [pscustomobject]@{ id = 'node-2'; service = 'shardkv-node-2'; url = 'http://localhost:8082' },
    [pscustomobject]@{ id = 'node-3'; service = 'shardkv-node-3'; url = 'http://localhost:8083' }
)

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Wait-ForCondition {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [Parameter(Mandatory)][string]$Description,
        [int]$TimeoutSeconds = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        try {
            if (& $Condition) { return }
        } catch {
            # Startup, shutdown, and health transitions are expected to fail transiently.
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Description."
}

function Get-Node {
    param([Parameter(Mandatory)][string]$Id)
    $node = $nodes | Where-Object { $_.id -eq $Id } | Select-Object -First 1
    if (-not $node) { throw "Unknown node ID returned by cluster: $Id" }
    return $node
}

function Test-AllNodesHealthy {
    foreach ($node in $nodes) {
        $health = Invoke-RestMethod "$($node.url)/actuator/health" -TimeoutSec 3
        if ($health.status -ne 'UP') { return $false }
        $cluster = Invoke-RestMethod "$($node.url)/cluster" -TimeoutSec 3
        if (@($cluster.members).Count -ne 3) { return $false }
        if (@($cluster.members | Where-Object { $_.status -ne 'HEALTHY' }).Count -ne 0) { return $false }
    }
    return $true
}

function Get-HttpStatus {
    param([Parameter(Mandatory)][string]$Uri)
    try {
        return [int](Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 10).StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Get-PrometheusValue {
    param([Parameter(Mandatory)][string]$Query)
    $encoded = [Uri]::EscapeDataString($Query)
    $response = Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$encoded" -TimeoutSec 5
    $result = @($response.data.result)
    if ($result.Count -eq 0) { return 0.0 }
    return [double]$result[0].value[1]
}

function Assert-PhysicalCopies {
    param(
        [Parameter(Mandatory)][string]$Key,
        [Parameter(Mandatory)][string]$ExpectedValue
    )
    $placement = Invoke-RestMethod "$($nodes[0].url)/cluster/replicas/$Key" -TimeoutSec 5
    $assigned = @($placement.primary) + @($placement.replicas)
    Assert-True ($assigned.Count -eq 3) "Expected three physical copies for $Key."
    foreach ($assignedNode in $assigned) {
        $node = Get-Node -Id $assignedNode.id
        $record = Invoke-RestMethod "$($node.url)/internal/record/$Key" -TimeoutSec 5
        Assert-True (-not [bool]$record.tombstone) "Replica $($node.id) contains a tombstone for $Key."
        Assert-True ($record.value -eq $ExpectedValue) "Replica $($node.id) has the wrong value for $Key."
        Assert-True ([long]$record.version -ge 1) "Replica $($node.id) has no valid version for $Key."
    }
}

function Invoke-Compose {
    param([Parameter(Mandatory)][string[]]$Arguments)
    & docker compose --project-name $ProjectName --file $ComposeFile @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

Write-Output 'Waiting for all three ShardKV nodes to report a healthy membership view...'
Wait-ForCondition -Description 'three healthy ShardKV nodes' -TimeoutSeconds 120 -Condition { Test-AllNodesHealthy }

$runId = [Guid]::NewGuid().ToString('N')
$key = "smoke-kv-$runId"
$value = "smoke-value-$runId"

$owners = @($nodes | ForEach-Object {
    (Invoke-RestMethod "$($_.url)/cluster/owner/$key" -TimeoutSec 5).owner.id
})
Assert-True ((@($owners | Select-Object -Unique)).Count -eq 1) 'Owner calculation differs across nodes.'
$primaryId = $owners[0]
$entryNode = $nodes | Where-Object { $_.id -ne $primaryId } | Select-Object -First 1
$readNode = $nodes | Where-Object { $_.id -ne $entryNode.id } | Select-Object -First 1

$body = @{ value = $value } | ConvertTo-Json -Compress
$put = Invoke-RestMethod -Method Put `
    -Uri "$($entryNode.url)/kv/$key`?consistency=ALL" `
    -ContentType 'application/json' -Body $body -TimeoutSec 15
Assert-True ($put.key -eq $key -and $put.value -eq $value) 'PUT through a non-primary returned unexpected data.'

$get = Invoke-RestMethod "$($readNode.url)/kv/$key`?consistency=QUORUM" -TimeoutSec 15
Assert-True ($get.value -eq $value) 'QUORUM GET through another node returned the wrong value.'
Assert-PhysicalCopies -Key $key -ExpectedValue $value

$documentKey = "smoke-document-$runId"
$city = "SmokeCity-$runId"
$documentBody = @{
    fields = @{
        city = $city
        role = 'SmokeRole'
        experience = 9
        active = $true
    }
} | ConvertTo-Json -Depth 4 -Compress
$document = Invoke-RestMethod -Method Put `
    -Uri "$($nodes[1].url)/documents/$documentKey`?consistency=ALL" `
    -ContentType 'application/json' -Body $documentBody -TimeoutSec 15
Assert-True ($document.key -eq $documentKey) 'Document PUT returned the wrong key.'

$queryBody = @{ filters = @{ city = $city; role = 'SmokeRole' } } | ConvertTo-Json -Depth 3 -Compress
$query = Invoke-RestMethod -Method Post -Uri "$($nodes[2].url)/query" `
    -ContentType 'application/json' -Body $queryBody -TimeoutSec 30
Assert-True ([bool]$query.complete) 'Distributed query was not complete.'
Assert-True (@($query.results | Where-Object { $_.key -eq $documentKey }).Count -eq 1) `
    'Distributed query did not return the inserted document.'

Wait-ForCondition -Description 'three healthy Prometheus scrape targets' -TimeoutSeconds 60 -Condition {
    $targets = (Invoke-RestMethod 'http://localhost:9090/api/v1/targets' -TimeoutSec 5).data.activeTargets
    $shardKvTargets = @($targets | Where-Object { $_.labels.job -eq 'shardkv' })
    return $shardKvTargets.Count -eq 3 -and @($shardKvTargets | Where-Object { $_.health -ne 'up' }).Count -eq 0
}

$grafanaHealth = Invoke-RestMethod 'http://localhost:3000/api/health' -TimeoutSec 5
Assert-True ($grafanaHealth.database -eq 'ok') 'Grafana health check did not report an available database.'
$credentials = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${GrafanaUser}:${GrafanaPassword}"))
$dashboardSearch = Invoke-RestMethod 'http://localhost:3000/api/search?query=ShardKV%20Cluster%20Overview' `
    -Headers @{ Authorization = "Basic $credentials" } -TimeoutSec 5
Assert-True (@($dashboardSearch | Where-Object { $_.title -eq 'ShardKV Cluster Overview' }).Count -eq 1) `
    'The provisioned ShardKV Grafana dashboard was not found.'

if ($VerifyPersistence) {
    Write-Output 'Restarting all ShardKV containers without deleting their volumes...'
    Invoke-Compose -Arguments @('restart', 'shardkv-node-1', 'shardkv-node-2', 'shardkv-node-3')
    Wait-ForCondition -Description 'cluster recovery after container restart' -TimeoutSeconds 120 -Condition { Test-AllNodesHealthy }
    $persisted = Invoke-RestMethod "$($nodes[0].url)/kv/$key`?consistency=ALL" -TimeoutSec 15
    Assert-True ($persisted.value -eq $value) 'Value did not survive the ShardKV container restart.'
    Assert-PhysicalCopies -Key $key -ExpectedValue $value
}

if ($VerifyFailureRecovery) {
    $failureKey = "smoke-failure-$runId"
    $failureValue = "failure-value-$runId"
    $failureBody = @{ value = $failureValue } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Put -Uri "$($nodes[0].url)/kv/$failureKey`?consistency=ALL" `
        -ContentType 'application/json' -Body $failureBody -TimeoutSec 15 | Out-Null
    $failedId = (Invoke-RestMethod "$($nodes[0].url)/cluster/owner/$failureKey" -TimeoutSec 5).owner.id
    $failedNode = Get-Node -Id $failedId
    $survivor = $nodes | Where-Object { $_.id -ne $failedId } | Select-Object -First 1
    $heartbeatFailuresBefore = Get-PrometheusValue 'sum(shardkv_heartbeat_total{result="failure"})'

    Write-Output "Stopping deterministic primary $failedId for the failure/recovery check..."
    Invoke-Compose -Arguments @('stop', $failedNode.service)
    try {
        Wait-ForCondition -Description "$failedId to become UNHEALTHY" -TimeoutSeconds 45 -Condition {
            $cluster = Invoke-RestMethod "$($survivor.url)/cluster" -TimeoutSec 5
            return ($cluster.members | Where-Object { $_.id -eq $failedId }).status -eq 'UNHEALTHY'
        }
        $one = Invoke-RestMethod "$($survivor.url)/kv/$failureKey`?consistency=ONE" -TimeoutSec 15
        $quorum = Invoke-RestMethod "$($survivor.url)/kv/$failureKey`?consistency=QUORUM" -TimeoutSec 15
        Assert-True ($one.value -eq $failureValue) 'ONE read failover returned the wrong value.'
        Assert-True ($quorum.value -eq $failureValue) 'QUORUM read failover returned the wrong value.'
        $allStatus = Get-HttpStatus -Uri "$($survivor.url)/kv/$failureKey`?consistency=ALL"
        Assert-True ($allStatus -eq 503) "Expected ALL read to fail with HTTP 503, received $allStatus."
        Wait-ForCondition -Description 'Prometheus heartbeat-failure observation' -TimeoutSeconds 30 -Condition {
            return (Get-PrometheusValue 'sum(shardkv_heartbeat_total{result="failure"})') -gt $heartbeatFailuresBefore
        }
    } finally {
        Invoke-Compose -Arguments @('start', $failedNode.service)
    }
    Wait-ForCondition -Description 'cluster health recovery after node restart' -TimeoutSeconds 90 -Condition { Test-AllNodesHealthy }
    Wait-ForCondition -Description 'three recovered Prometheus scrape targets' -TimeoutSeconds 30 -Condition {
        $targets = (Invoke-RestMethod 'http://localhost:9090/api/v1/targets' -TimeoutSec 5).data.activeTargets
        $shardKvTargets = @($targets | Where-Object { $_.labels.job -eq 'shardkv' })
        return $shardKvTargets.Count -eq 3 -and @($shardKvTargets | Where-Object { $_.health -ne 'up' }).Count -eq 0
    }
}

Write-Output "PASS: owner=$primaryId, non-primary entry=$($entryNode.id), QUORUM read=$($readNode.id), RF=3 physical copies verified."
Write-Output 'PASS: document mutation, distributed query, Prometheus targets, and Grafana provisioning verified.'
if ($VerifyPersistence) { Write-Output 'PASS: RocksDB value and all physical replicas survived container restart.' }
if ($VerifyFailureRecovery) { Write-Output 'PASS: health transition, Prometheus failure signal, ONE/QUORUM read failover, ALL failure, and recovery verified.' }
