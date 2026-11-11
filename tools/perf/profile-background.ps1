param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [string]$Device,
    [ValidateRange(10, 60)][int]$Seconds = 40,
    [string]$Label = 'background'
)
$ErrorActionPreference = 'Stop'
if ($Label -notmatch '^[A-Za-z0-9_-]+$') { throw 'Use a simple file label' }
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $project 'build/profiles'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$selection = if ($Device) { @('-s', $Device) } else { @() }
$remote = '/data/local/tmp/gouge-profile-' + [guid]::NewGuid().ToString('N') + '.data'
try {
    # --app configures supported profiling permissions before switching to the app UID.
    & $adb @selection shell simpleperf record --app com.example.installlock -e cpu-clock -f 499 -g `
        --duration $Seconds -o $remote
    if ($LASTEXITCODE -ne 0) { throw 'Recording failed' }
    $report = & $adb @selection shell simpleperf report -i $remote --sort symbol --children --percent-limit 0.1
    if ($LASTEXITCODE -ne 0) { throw 'Reporting failed' }
    $report | Set-Content -LiteralPath (Join-Path $output ($Label + '.txt')) -Encoding utf8
    $report | Select-String 'Samples:|Event count:|com.example.installlock|ApplicationPackageManager.getInstalledPackages'
} finally {
    & $adb @selection shell rm -f $remote
}
