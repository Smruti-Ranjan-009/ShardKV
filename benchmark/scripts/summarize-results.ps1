[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

function Get-Median {
    param([double[]]$Values)
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $middle = [Math]::Floor($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Format-Number {
    param([double]$Value)
    return $Value.ToString('0.00', [Globalization.CultureInfo]::InvariantCulture)
}

function Format-Percent {
    param([double]$Value)
    return ($Value * 100.0).ToString('0.000', [Globalization.CultureInfo]::InvariantCulture) + '%'
}

$rawRoot = Join-Path $script:ResultsRoot 'raw'
if (-not (Test-Path -LiteralPath $rawRoot)) {
    throw 'No raw benchmark result directory exists.'
}
$documents = @()
foreach ($file in Get-ChildItem -LiteralPath $rawRoot -Filter '*.json' | Sort-Object Name) {
    try {
        $document = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        $documents += [pscustomobject]@{ File = $file.Name; Data = $document }
    } catch {
        Write-Warning "Discarding unreadable result $($file.Name): $($_.Exception.Message)"
    }
}
if ($documents.Count -eq 0) { throw 'No benchmark results were found.' }

$runRows = foreach ($document in $documents) {
    $data = $document.Data
    $hasExecution = $data.PSObject.Properties.Name -contains 'execution'
    [pscustomobject]@{
        file = $document.File
        timestamp = $data.timestamp
        experiment = $data.metadata.experiment
        nodeCount = [int]$data.metadata.nodeCount
        replicationFactor = [int]$data.metadata.replicationFactor
        consistency = [string]$data.metadata.consistency
        workload = [string]$data.metadata.workload
        payloadBytes = [int]$data.metadata.payloadBytes
        datasetSize = [int]$data.metadata.datasetSize
        vus = [int]$data.metadata.vus
        warmupSeconds = [int]$data.metadata.warmupSeconds
        durationSeconds = [int]$data.metadata.durationSeconds
        runNumber = [int]$data.metadata.runNumber
        throughputOpsPerSecond = [double]$data.metrics.throughputOpsPerSecond
        p50Ms = [double]$data.metrics.latencyMs.p50
        p95Ms = [double]$data.metrics.latencyMs.p95
        p99Ms = [double]$data.metrics.latencyMs.p99
        errorRate = [double]$data.metrics.errorRate
        javaCpuSecondsDuringRun = if ($hasExecution) { [double]$data.execution.javaCpuSecondsDuringRun } else { $null }
        javaWorkingSetBytesAfter = if ($hasExecution) { [long]$data.execution.javaWorkingSetBytesAfter } else { $null }
        valid = [bool]$data.validation.valid
        invalidReason = if ($data.validation.PSObject.Properties.Name -contains 'reason') {
            [string]$data.validation.reason
        } elseif (-not [bool]$data.validation.valid) {
            'scenario-specific validation failed; inspect the raw result validation object'
        } else {
            $null
        }
    }
}
$runsPath = Join-Path $script:ResultsRoot 'runs.csv'
$runRows | Export-Csv -LiteralPath $runsPath -NoTypeInformation -Encoding UTF8

$healthyRows = @($runRows | Where-Object { $_.experiment -ne 'failure-under-load' -and $_.valid })
$grouped = $healthyRows | Group-Object {
    "$($_.experiment)|$($_.nodeCount)|$($_.replicationFactor)|$($_.consistency)|$($_.workload)|$($_.payloadBytes)|$($_.datasetSize)|$($_.vus)|$($_.warmupSeconds)|$($_.durationSeconds)"
}
$summaries = foreach ($group in $grouped) {
    $first = $group.Group[0]
    [pscustomobject]@{
        experiment = $first.experiment
        nodeCount = $first.nodeCount
        replicationFactor = $first.replicationFactor
        consistency = $first.consistency
        workload = $first.workload
        payloadBytes = $first.payloadBytes
        datasetSize = $first.datasetSize
        vus = $first.vus
        warmupSeconds = $first.warmupSeconds
        durationSeconds = $first.durationSeconds
        validRuns = $group.Count
        medianThroughputOpsPerSecond = Get-Median @($group.Group.throughputOpsPerSecond)
        medianP50Ms = Get-Median @($group.Group.p50Ms)
        medianP95Ms = Get-Median @($group.Group.p95Ms)
        medianP99Ms = Get-Median @($group.Group.p99Ms)
        medianErrorRate = Get-Median @($group.Group.errorRate)
        medianJavaCpuSecondsDuringRun = Get-Median @($group.Group.javaCpuSecondsDuringRun)
        medianJavaWorkingSetBytesAfter = Get-Median @($group.Group.javaWorkingSetBytesAfter)
    }
}
$summaries = @($summaries | Sort-Object experiment, nodeCount, replicationFactor, consistency, workload, vus)
$summaries | Export-Csv -LiteralPath (Join-Path $script:ResultsRoot 'summary.csv') -NoTypeInformation -Encoding UTF8
$summaries | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $script:ResultsRoot 'summary.json') -Encoding UTF8

$environmentPath = Join-Path $script:ResultsRoot 'environment.json'
$environment = if (Test-Path -LiteralPath $environmentPath) {
    Get-Content -LiteralPath $environmentPath -Raw | ConvertFrom-Json
} else { $null }
$markdown = New-Object Collections.Generic.List[string]
$markdown.Add('# ShardKV Phase 8 benchmark results')
$markdown.Add('')
$markdown.Add('Generated exclusively from `benchmark/results/raw/*.json`; no result values are hand-entered.')
$markdown.Add('')
if ($environment) {
    $ramGiB = [double]$environment.machine.ramBytes / 1GB
    $markdown.Add('## Environment')
    $markdown.Add('')
    $markdown.Add("- Captured: $($environment.capturedAt)")
    $markdown.Add("- CPU: $($environment.machine.cpu)")
    $markdown.Add("- Cores: $($environment.machine.physicalCores) physical / $($environment.machine.logicalCores) logical")
    $markdown.Add(("- RAM: {0:N2} GiB" -f $ramGiB))
    $markdown.Add("- OS: $($environment.operatingSystem.caption) $($environment.operatingSystem.version), build $($environment.operatingSystem.buildNumber)")
    $markdown.Add(('- k6: `{0}`' -f $environment.k6Image))
    $markdown.Add("- JVM: $($environment.jvmArguments)")
    $markdown.Add('')
}
$markdown.Add('## Method')
$markdown.Add('')
if ($summaries.Count -gt 0) {
    $first = $summaries[0]
    $markdown.Add("- Dataset: $($first.datasetSize) deterministic point keys plus a separate delete-key set")
    $markdown.Add("- Value payload: $($first.payloadBytes) bytes")
    $markdown.Add("- Headline warm-up / measurement: $($first.warmupSeconds)s / $($first.durationSeconds)s")
}
$markdown.Add('- Headline results are medians of valid repetitions; individual runs are retained in `runs.csv`.')
$markdown.Add('- Healthy runs are invalidated by failed k6 thresholds, excessive errors, or unhealthy cluster state.')
$markdown.Add('')

function Add-Table {
    param(
        [string]$Title,
        [object[]]$Rows,
        [string[]]$Columns,
        [scriptblock]$Render
    )
    $markdown.Add("## $Title")
    $markdown.Add('')
    if (-not $Rows -or $Rows.Count -eq 0) {
        $markdown.Add('_No valid runs._')
        $markdown.Add('')
        return
    }
    $markdown.Add('| ' + ($Columns -join ' | ') + ' |')
    $markdown.Add('| ' + (($Columns | ForEach-Object { '---' }) -join ' | ') + ' |')
    foreach ($row in $Rows) { $markdown.Add((& $Render $row)) }
    $markdown.Add('')
}

$metricColumns = @('Nodes', 'RF', 'Consistency', 'Workload', 'VUs', 'Runs', 'Ops/s', 'P50 ms', 'P95 ms', 'P99 ms', 'Errors')
$metricRender = {
    param($row)
    '| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} |' -f `
        $row.nodeCount, $row.replicationFactor, $row.consistency, $row.workload, $row.vus, $row.validRuns,
        (Format-Number $row.medianThroughputOpsPerSecond), (Format-Number $row.medianP50Ms),
        (Format-Number $row.medianP95Ms), (Format-Number $row.medianP99Ms),
        (Format-Percent $row.medianErrorRate)
}
Add-Table 'Horizontal sharding scale (RF=1)' @($summaries | Where-Object experiment -eq 'horizontal-scale') $metricColumns $metricRender
Add-Table 'Read-heavy scale (RF=1)' @($summaries | Where-Object { $_.experiment -eq 'horizontal-scale' -and $_.workload -eq 'get-heavy' }) $metricColumns $metricRender
Add-Table 'Replication cost (3 nodes, ALL)' @($summaries | Where-Object experiment -eq 'replication-cost') $metricColumns $metricRender
Add-Table 'Consistency cost (3 nodes, RF=3)' @($summaries | Where-Object experiment -eq 'consistency-cost') $metricColumns $metricRender
Add-Table 'Concurrency sweep (3 nodes, RF=1)' @($summaries | Where-Object experiment -eq 'concurrency-sweep') $metricColumns $metricRender
Add-Table 'Distributed query' @($summaries | Where-Object experiment -eq 'distributed-query') $metricColumns $metricRender

$failure = $documents | Where-Object { $_.Data.metadata.experiment -eq 'failure-under-load' } | Select-Object -Last 1
$failureSummaryPath = Join-Path $script:ResultsRoot 'failure-summary.json'
if ($failure) {
    $failure.Data | Select-Object metadata, metrics, consistencyBreakdown, failureInjection, prometheusCorrelation, validation |
        ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $failureSummaryPath -Encoding UTF8
}
$markdown.Add('## Failure under load')
$markdown.Add('')
if ($failure) {
    $data = $failure.Data
    $markdown.Add("- Valid: $($data.validation.valid)")
    $markdown.Add("- Failed node: $($data.failureInjection.failedNode)")
    $markdown.Add("- Actual node-down interval: $($data.failureInjection.actualNodeDownSeconds)s")
    $markdown.Add("- UNHEALTHY detection: $($data.failureInjection.unhealthyDetectionSeconds)s")
    $markdown.Add("- HEALTHY recovery after restart: $($data.failureInjection.healthyRecoverySeconds)s")
    $markdown.Add("- QUORUM error rate: $(Format-Percent ([double]$data.consistencyBreakdown.quorum.errorRate))")
    $markdown.Add("- ALL error rate across the full run: $(Format-Percent ([double]$data.consistencyBreakdown.all.errorRate))")
    $markdown.Add("- Overall P95 / P99: $(Format-Number ([double]$data.metrics.latencyMs.p95)) ms / $(Format-Number ([double]$data.metrics.latencyMs.p99)) ms")
} else {
    $markdown.Add('_No failure-injection run._')
}
$markdown.Add('')
$invalid = @($runRows | Where-Object { -not $_.valid })
$markdown.Add('## Invalid or discarded runs')
$markdown.Add('')
if ($invalid.Count -eq 0) {
    $markdown.Add('None.')
} else {
    foreach ($row in $invalid) {
        $markdown.Add(('- `{0}`: {1}' -f $row.file, $row.invalidReason))
    }
}
$markdown.Add('')
$markdown.Add('## Scope warning')
$markdown.Add('')
$markdown.Add('These results describe one Windows development host using loopback networking and shared CPU, memory, disk, Docker, and JVM resources. Local multi-JVM scaling is not equivalent to multi-machine or production capacity.')
$markdown | Set-Content -LiteralPath (Join-Path $script:ResultsRoot 'summary.md') -Encoding UTF8
Write-Output "Summarized $($runRows.Count) individual run(s), including $($healthyRows.Count) valid healthy run(s)."
