param(
    [Parameter(Mandatory=$true)][string]$BaselineLib,
    [Parameter(Mandatory=$true)][string]$CandidateLib,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [Parameter(Mandatory=$true)][string]$SyntheticInput,
    [Parameter(Mandatory=$true)][string]$HinodeInput,
    [Parameter(Mandatory=$true)][string]$ElektroInput,
    [string]$JavaHome = 'C:\Users\dario\.jdks\openjdk-25.0.2'
)

$ErrorActionPreference = 'Stop'
$baseline = (Resolve-Path -LiteralPath $BaselineLib).Path
$candidate = (Resolve-Path -LiteralPath $CandidateLib).Path
$bundle = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
$inputs = @{
    synthetic = (Resolve-Path -LiteralPath $SyntheticInput).Path
    hinode = (Resolve-Path -LiteralPath $HinodeInput).Path
    elektro = (Resolve-Path -LiteralPath $ElektroInput).Path
}
$hashes = @{
    synthetic = 'beb56883ee339d919630ac097e7e238f0397a31ff1d1efdf72cdf92a6f8b4c8e'
    hinode = '025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0'
    elektro = 'effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529'
}
foreach ($name in $inputs.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $inputs[$name]).Hash -ne $hashes[$name]) {
        throw "Unexpected $name input hash"
    }
}
$libraries = @{
    baseline = $baseline
    candidate = $candidate
}
$classes = Join-Path $bundle 'benchmark-classes'
if (Test-Path -LiteralPath $classes) { throw "Benchmark output already exists: $classes" }
New-Item -ItemType Directory -Path $classes | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$modules = 'eu.dariolucia.jsatdecode.decoder.hinode,eu.dariolucia.jsatdecode.decoder.elektrol.ggak,eu.dariolucia.jsatdecode.source.file,eu.dariolucia.jsatdecode.core'
$sources = @('HinodeBaselineReplay.java','ElektroBaselineReplay.java','IqMemoryReplay.java',
             'IqMissionWarmBenchmark.java','IqMissionWarmHeapBenchmark.java') |
        ForEach-Object { Join-Path $PSScriptRoot $_ }
& $javac --module-path $baseline --add-modules $modules -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Benchmark compilation failed' }

function Run-Replay([string]$tag, [string]$kind, [string]$main, [string[]]$replayArgs) {
    $log = Join-Path $bundle "$tag.log"
    if (Test-Path -LiteralPath $log) { throw "Benchmark output already exists: $log" }
    $jfr = '-XX:StartFlightRecording=filename=' + (Join-Path $bundle "$tag.jfr") + ',settings=profile,dumponexit=true'
    $gc = '-Xlog:gc*:file=' + (Join-Path $bundle "$tag-gc.log") + ':time,level,tags'
    $ErrorActionPreference = 'Continue'
    & $java -Xms256m -Xmx512m $jfr $gc '-Djsatdecode.replay.leased=true' --module-path $libraries[$kind] --add-modules $modules `
        -cp $classes $main $replayArgs *> $log
    $replayExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($replayExit -ne 0) { throw "Replay failed: $tag" }
    Write-Output "Finished $tag"
}

for ($run = 1; $run -le 3; $run++) {
    foreach ($kind in @('baseline', 'candidate')) {
        Run-Replay "synthetic-$run-$kind" $kind 'IqMemoryReplay' @('replay', $inputs.synthetic)
    }
}
foreach ($mission in @('hinode', 'elektro')) {
    for ($run = 1; $run -le 3; $run++) {
        foreach ($kind in @('baseline', 'candidate')) {
            $tag = "warm-$mission-$run-$kind"
            Run-Replay $tag $kind 'IqMissionWarmHeapBenchmark' @($mission, $inputs[$mission], (Join-Path $bundle "$tag-frames.bin"))
        }
    }
}
