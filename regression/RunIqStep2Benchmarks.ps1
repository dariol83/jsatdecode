param(
    [Parameter(Mandatory=$true)][string]$BaselineWorkspace,
    [Parameter(Mandatory=$true)][string]$CandidateWorkspace,
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [Parameter(Mandatory=$true)][string]$SyntheticInput,
    [Parameter(Mandatory=$true)][string]$HinodeInput,
    [Parameter(Mandatory=$true)][string]$ElektroInput,
    [string]$JavaHome = 'C:\Users\dario\.jdks\openjdk-25.0.2'
)

$ErrorActionPreference = 'Stop'
$baseline = (Resolve-Path -LiteralPath $BaselineWorkspace).Path
$candidate = (Resolve-Path -LiteralPath $CandidateWorkspace).Path
$bundle = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
$inputs = @{
    synthetic = (Resolve-Path -LiteralPath $SyntheticInput).Path
    hinode = (Resolve-Path -LiteralPath $HinodeInput).Path
    elektro = (Resolve-Path -LiteralPath $ElektroInput).Path
}
$expectedHashes = @{
    synthetic = 'beb56883ee339d919630ac097e7e238f0397a31ff1d1efdf72cdf92a6f8b4c8e'
    hinode = '025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0'
    elektro = 'effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529'
}
foreach ($name in $inputs.Keys) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $inputs[$name]).Hash -ne $expectedHashes[$name]) {
        throw "Unexpected $name input hash"
    }
}
$modules = 'eu.dariolucia.jsatdecode.decoder.hinode,eu.dariolucia.jsatdecode.decoder.elektrol.ggak,eu.dariolucia.jsatdecode.source.file,eu.dariolucia.jsatdecode.core'
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$libraries = @{}
$classes = @{}
foreach ($entry in @(@('baseline', $baseline), @('candidate', $candidate))) {
    $kind = $entry[0]
    $workspace = $entry[1]
    $package = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'deployment/target/deployment-smoke-test') -Directory |
            Where-Object { $_.Name -like 'jsatdecoder package*' } | Sort-Object LastWriteTime -Descending)
    if ($package.Count -eq 0) { throw "No built deployment in $workspace" }
    $libraries[$kind] = Join-Path $package[0].FullName 'jsatdecode-0.1.0-SNAPSHOT/lib'
    $classes[$kind] = Join-Path $bundle "final-$kind-classes"
    if (Test-Path -LiteralPath $classes[$kind]) { throw "Benchmark output already exists: $($classes[$kind])" }
    New-Item -ItemType Directory -Path $classes[$kind] | Out-Null
    $sources = @('HinodeBaselineReplay.java', 'ElektroBaselineReplay.java', 'IqMemoryReplay.java') |
            ForEach-Object { Join-Path $workspace "regression/$_" }
    $ErrorActionPreference = 'Continue'
    & $javac --module-path $libraries[$kind] --add-modules $modules -d $classes[$kind] $sources
    $compilerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($compilerExit -ne 0) { throw "$kind replay compilation failed" }
    $ErrorActionPreference = 'Continue'
    & $javac --module-path $libraries[$kind] --add-modules $modules -cp $classes[$kind] -d $classes[$kind] (Join-Path $workspace 'regression/IqMissionWarmBenchmark.java')
    $compilerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($compilerExit -ne 0) { throw "$kind warmup compilation failed" }
}

function Run-Replay([string]$tag, [string]$kind, [string]$main, [string[]]$replayArgs) {
    $log = Join-Path $bundle "$tag.log"
    if (Test-Path -LiteralPath $log) { throw "Benchmark output already exists: $log" }
    $jfr = '-XX:StartFlightRecording=filename=' + (Join-Path $bundle "$tag.jfr") + ',settings=profile,dumponexit=true'
    $gc = '-Xlog:gc*:file=' + (Join-Path $bundle "$tag-gc.log") + ':time,level,tags'
    $leased = '-Djsatdecode.replay.leased=' + ($kind -eq 'candidate').ToString().ToLowerInvariant()
    $ErrorActionPreference = 'Continue'
    & $java -Xms256m -Xmx512m $jfr $gc $leased --module-path $libraries[$kind] --add-modules $modules `
        -cp $classes[$kind] $main $replayArgs *> $log
    $replayExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($replayExit -ne 0) { throw "Replay failed: $tag" }
    Write-Output "Finished $tag"
}

for ($run = 1; $run -le 3; $run++) {
    foreach ($kind in @('baseline', 'candidate')) {
        Run-Replay "final-synthetic-$run-$kind" $kind 'IqMemoryReplay' @('replay', $inputs.synthetic)
    }
}
foreach ($mission in @('hinode', 'elektro')) {
    for ($run = 1; $run -le 3; $run++) {
        foreach ($kind in @('baseline', 'candidate')) {
            $tag = "final-warm-$mission-$run-$kind"
            Run-Replay $tag $kind 'IqMissionWarmBenchmark' @($mission, $inputs[$mission], (Join-Path $bundle "$tag-frames.bin"))
        }
    }
}
