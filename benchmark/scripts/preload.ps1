[CmdletBinding()]
param(
    [ValidateSet('kv', 'documents', 'both')][string]$Mode = 'kv',
    [string]$ConfigPath
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$config = if ($ConfigPath) { Get-BenchmarkConfig -Path $ConfigPath } else { Get-BenchmarkConfig }
$state = Get-ClusterState
Wait-ClusterHealthy -State $state -TimeoutSeconds 60
$environment = @{
    BASE_URL = "http://host.docker.internal:$($state.nodes[0].port)"
    DATASET_SIZE = [string]$config.datasetSize
    PRELOAD_VUS = [string]$config.preloadVus
    PAYLOAD_BYTES = [string]$config.payloadBytes
    CONSISTENCY = 'ALL'
}

if ($Mode -in @('kv', 'both')) {
    $exitCode = Invoke-K6Container -Image $config.k6Image -Script 'preload.js' -Environment $environment
    if ($exitCode -ne 0) {
        throw "KV preload failed with k6 exit code $exitCode."
    }
    $kvPlacement = Assert-DatasetPlacement -State $state -KeyPrefix 'bench-key' -DatasetSize ([int]$config.datasetSize)
    Write-Output "Validated KV primary placement over a deterministic sample: $kvPlacement"
}
if ($Mode -in @('documents', 'both')) {
    $exitCode = Invoke-K6Container -Image $config.k6Image -Script 'preload-documents.js' -Environment $environment
    if ($exitCode -ne 0) {
        throw "Document preload failed with k6 exit code $exitCode."
    }
    $documentPlacement = Assert-DatasetPlacement -State $state -KeyPrefix 'bench-doc' -DatasetSize ([int]$config.datasetSize)
    Write-Output "Validated document primary placement over a deterministic sample: $documentPlacement"
}

Wait-ClusterHealthy -State $state -TimeoutSeconds 60
Write-Output "Preloaded $($config.datasetSize) deterministic item(s) for mode $Mode."
