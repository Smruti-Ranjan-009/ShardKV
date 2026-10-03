[CmdletBinding()]
param(
    [string]$ConfigPath
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$config = if ($ConfigPath) { Get-BenchmarkConfig -Path $ConfigPath } else { Get-BenchmarkConfig }
Assert-DockerReady
$cpu = Get-CimInstance Win32_Processor
$computer = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$javaVersion = (& cmd.exe /d /c 'java -version 2>&1') -join "`n"
$dockerVersion = (& docker version --format '{{.Server.Version}}') -join ''
$k6Output = & cmd.exe /d /c "docker run --rm $($config.k6Image) version 2>&1"
$k6Version = ($k6Output | Where-Object { $_ -match '^k6 v' } | Select-Object -Last 1)
if (-not $k6Version) { $k6Version = $k6Output -join "`n" }
$gitCommit = (& git -C $script:RepoRoot rev-parse HEAD) -join ''
$gitDirty = [bool]((& git -C $script:RepoRoot status --porcelain) -join '')

$environment = [ordered]@{
    capturedAt = (Get-Date).ToUniversalTime().ToString('o')
    machine = [ordered]@{
        cpu = (($cpu | ForEach-Object { $_.Name.Trim() }) -join '; ')
        physicalCores = [int](($cpu | Measure-Object -Property NumberOfCores -Sum).Sum)
        logicalCores = [int](($cpu | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum)
        ramBytes = [long]$computer.TotalPhysicalMemory
    }
    operatingSystem = [ordered]@{
        caption = $os.Caption
        version = $os.Version
        buildNumber = $os.BuildNumber
        architecture = $os.OSArchitecture
    }
    javaVersion = $javaVersion
    jvmArguments = 'No explicit heap flags; default Java 17 ergonomics'
    dockerVersion = $dockerVersion
    k6Image = $config.k6Image
    k6Version = $k6Version
    repositoryCommit = $gitCommit
    repositoryDirtyDuringCapture = $gitDirty
    benchmarkConfig = $config
}

New-Item -ItemType Directory -Force -Path $script:ResultsRoot | Out-Null
$output = Join-Path $script:ResultsRoot 'environment.json'
$environment | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $output -Encoding UTF8
Write-Output $output
