[CmdletBinding()]
param(
    [switch]$RemoveRunData
)

. (Join-Path $PSScriptRoot 'Common.ps1')

if (-not (Test-Path -LiteralPath $script:StatePath)) {
    Write-Output 'No tracked benchmark cluster is running.'
    exit 0
}

$state = Get-ClusterState
foreach ($node in $state.nodes) {
    Stop-TrackedNode -Node $node
}
Remove-Item -LiteralPath $script:StatePath -Force

if ($RemoveRunData) {
    $runRoot = Assert-SafeRuntimePath -Path $state.runRoot
    if (Test-Path -LiteralPath $runRoot) {
        Remove-Item -LiteralPath $runRoot -Recurse -Force
    }
}
Write-Output "Stopped $($state.nodeCount) tracked ShardKV JVM(s) for run $($state.runId)."
