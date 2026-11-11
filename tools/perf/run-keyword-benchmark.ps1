param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$Device,
    [switch]$Phone
)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$classes = Join-Path $project 'build/perf-classes'
$dex = Join-Path $project 'build/perf-dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$env:JAVA_HOME = $JavaHome
& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -d $classes `
    (Join-Path $project 'app/src/main/java/com/example/installlock/KeywordScanner.java') `
    (Join-Path $PSScriptRoot 'KeywordScannerBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }
if (-not $Phone) {
    & (Join-Path $JavaHome 'bin/java.exe') -cp $classes KeywordScannerBenchmark
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark failed' }
    exit
}
$androidJar = Get-ChildItem (Join-Path $SdkRoot 'platforms') -Filter android.jar -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
$d8 = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Filter d8.bat -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $androidJar -or -not $d8) { throw 'Install Android platform and build tools first' }
$inputs = @(Get-ChildItem $classes -Filter '*.class' -Recurse | ForEach-Object FullName)
& $d8.FullName --release --min-api 30 --lib $androidJar.FullName --output $dex @inputs
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$selection = if ($Device) { @('-s', $Device) } else { @() }
$remote = '/data/local/tmp/gouge-keyword-benchmark-' + [guid]::NewGuid().ToString('N') + '.dex'
try {
    & $adb @selection push (Join-Path $dex 'classes.dex') $remote
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark upload failed' }
    & $adb @selection shell "CLASSPATH=$remote app_process /system/bin KeywordScannerBenchmark"
    if ($LASTEXITCODE -ne 0) { throw 'Android benchmark failed' }
} finally {
    # Only the exact temporary path created by this run is removed.
    & $adb @selection shell rm $remote
}
