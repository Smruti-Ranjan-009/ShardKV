[CmdletBinding()]
param()

. (Join-Path $PSScriptRoot 'Common.ps1')

$summaryPath = Join-Path $script:ResultsRoot 'summary.json'
if (-not (Test-Path -LiteralPath $summaryPath)) { throw 'Run summarize-results.ps1 first.' }
$parsedSummaries = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
$summaries = @(foreach ($summary in $parsedSummaries) { $summary })
$chartRoot = Join-Path $script:ResultsRoot 'charts'
New-Item -ItemType Directory -Force -Path $chartRoot | Out-Null

function Escape-Svg([string]$Value) {
    return [Security.SecurityElement]::Escape($Value)
}

function Write-BarChart {
    param(
        [string]$Title,
        [string]$YAxis,
        [object[]]$Items,
        [string]$OutputName
    )
    if (-not $Items -or $Items.Count -eq 0) { return }
    $width = 800
    $height = 450
    $left = 85
    $top = 55
    $bottom = 75
    $plotWidth = $width - $left - 30
    $plotHeight = $height - $top - $bottom
    $maxValue = [double](($Items | Measure-Object -Property value -Maximum).Maximum)
    if ($maxValue -le 0) { $maxValue = 1 }
    $axisMax = $maxValue * 1.1
    $slot = $plotWidth / $Items.Count
    $barWidth = [Math]::Min(90, $slot * 0.58)
    $svg = New-Object Collections.Generic.List[string]
    $svg.Add("<svg xmlns=`"http://www.w3.org/2000/svg`" width=`"$width`" height=`"$height`" viewBox=`"0 0 $width $height`">")
    $svg.Add('<rect width="100%" height="100%" fill="white"/>')
    $svg.Add("<text x=`"$($width / 2)`" y=`"28`" text-anchor=`"middle`" font-family=`"sans-serif`" font-size=`"20`">$(Escape-Svg $Title)</text>")
    $svg.Add("<line x1=`"$left`" y1=`"$top`" x2=`"$left`" y2=`"$($top + $plotHeight)`" stroke=`"#333`"/>")
    $svg.Add("<line x1=`"$left`" y1=`"$($top + $plotHeight)`" x2=`"$($left + $plotWidth)`" y2=`"$($top + $plotHeight)`" stroke=`"#333`"/>")
    $svg.Add("<text x=`"18`" y=`"$($top + $plotHeight / 2)`" transform=`"rotate(-90 18 $($top + $plotHeight / 2))`" text-anchor=`"middle`" font-family=`"sans-serif`" font-size=`"13`">$(Escape-Svg $YAxis)</text>")
    for ($tick = 0; $tick -le 4; $tick++) {
        $value = $axisMax * $tick / 4
        $y = $top + $plotHeight - ($plotHeight * $tick / 4)
        $svg.Add("<line x1=`"$left`" y1=`"$y`" x2=`"$($left + $plotWidth)`" y2=`"$y`" stroke=`"#ddd`"/>")
        $svg.Add("<text x=`"$($left - 8)`" y=`"$($y + 4)`" text-anchor=`"end`" font-family=`"sans-serif`" font-size=`"11`">$($value.ToString('0.##',[Globalization.CultureInfo]::InvariantCulture))</text>")
    }
    for ($index = 0; $index -lt $Items.Count; $index++) {
        $item = $Items[$index]
        $barHeight = $plotHeight * [double]$item.value / $axisMax
        $x = $left + ($slot * $index) + (($slot - $barWidth) / 2)
        $y = $top + $plotHeight - $barHeight
        $svg.Add("<rect x=`"$x`" y=`"$y`" width=`"$barWidth`" height=`"$barHeight`" fill=`"#3274d9`"/>")
        $svg.Add("<text x=`"$($x + $barWidth / 2)`" y=`"$($y - 6)`" text-anchor=`"middle`" font-family=`"sans-serif`" font-size=`"11`">$(([double]$item.value).ToString('0.##',[Globalization.CultureInfo]::InvariantCulture))</text>")
        $svg.Add("<text x=`"$($x + $barWidth / 2)`" y=`"$($top + $plotHeight + 22)`" text-anchor=`"middle`" font-family=`"sans-serif`" font-size=`"12`">$(Escape-Svg ([string]$item.label))</text>")
    }
    $svg.Add('</svg>')
    $svg | Set-Content -LiteralPath (Join-Path $chartRoot $OutputName) -Encoding UTF8
}

$horizontal = @($summaries | Where-Object { $_.experiment -eq 'horizontal-scale' -and $_.workload -eq 'get-heavy' } | Sort-Object nodeCount | ForEach-Object {
    [pscustomobject]@{ label = "$($_.nodeCount) node(s)"; value = [double]$_.medianThroughputOpsPerSecond }
})
Write-BarChart 'Read-heavy throughput vs node count (RF=1)' 'operations / second' $horizontal 'horizontal-throughput.svg'

$replication = @($summaries | Where-Object experiment -eq 'replication-cost' | Sort-Object replicationFactor | ForEach-Object {
    [pscustomobject]@{ label = "RF=$($_.replicationFactor)"; value = [double]$_.medianThroughputOpsPerSecond }
})
Write-BarChart 'PUT-heavy throughput vs replication factor (ALL)' 'operations / second' $replication 'replication-throughput.svg'

$order = @{ ONE = 1; QUORUM = 2; ALL = 3 }
$consistency = @($summaries | Where-Object experiment -eq 'consistency-cost' | Sort-Object { $order[$_.consistency] } | ForEach-Object {
    [pscustomobject]@{ label = $_.consistency; value = [double]$_.medianP95Ms }
})
Write-BarChart 'PUT-heavy P95 latency vs consistency (3 nodes, RF=3)' 'P95 latency (ms)' $consistency 'consistency-p95.svg'
Write-Output "Generated charts in $chartRoot"
