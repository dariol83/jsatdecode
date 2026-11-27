param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)

$ErrorActionPreference = 'Stop'
$bundle = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
function Median([double[]]$values) {
    $ordered = @($values | Sort-Object)
    if ($ordered.Count -ne 3) { throw 'Expected three benchmark processes' }
    return $ordered[1]
}
function SyntheticMedian([string]$file, [string]$mission) {
    $values = @()
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match "^$mission run=[012] .* Msamples_s=([0-9.]+)$") {
            $values += [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return Median $values
}
function Number([string]$file, [string]$pattern) {
    $values = @(Get-Content -LiteralPath $file | Where-Object { $_ -match $pattern })
    if ($values.Count -ne 1 -or $values[0] -notmatch $pattern) { throw "Missing/duplicate $pattern in $file" }
    return [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
}
function SourceAllocated([string]$file) {
    $lines = @(Get-Content -LiteralPath $file | Where-Object { $_ -match '^sampled_source_thread_allocated_bytes=\{' })
    if ($lines.Count -ne 1 -or $lines[0] -notmatch '^sampled_source_thread_allocated_bytes=\{(.+)\}$') {
        throw "Missing source-thread samples in $file"
    }
    $entries = @($Matches[1] -split ', ')
    if ($entries.Count -ne 2 -or $entries[-1] -notmatch '^\d+=(\d+)$') { throw "Expected warm-up and replay source threads in $file" }
    return [double]$Matches[1]
}

$results = @()
foreach ($mission in @('hinode', 'elektro')) {
    $baseline = @(); $candidate = @()
    $realBaseline = @(); $realCandidate = @(); $heapBaseline = @(); $heapCandidate = @()
    $allocBaseline = @(); $allocCandidate = @()
    foreach ($run in 1..3) {
        $baseline += SyntheticMedian (Join-Path $bundle "synthetic-$run-baseline.log") $mission
        $candidate += SyntheticMedian (Join-Path $bundle "synthetic-$run-candidate.log") $mission
        $before = Join-Path $bundle "warm-$mission-$run-baseline.log"
        $after = Join-Path $bundle "warm-$mission-$run-candidate.log"
        $realBaseline += Number $before 'wall_seconds=([0-9.]+)'
        $realCandidate += Number $after 'wall_seconds=([0-9.]+)'
        $heapBaseline += Number $before '^sampled_peak_heap_bytes=(\d+)'
        $heapCandidate += Number $after '^sampled_peak_heap_bytes=(\d+)'
        $allocBaseline += SourceAllocated $before
        $allocCandidate += SourceAllocated $after
        $beforeFrames = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $bundle "warm-$mission-$run-baseline-frames.bin")).Hash
        $afterFrames = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $bundle "warm-$mission-$run-candidate-frames.bin")).Hash
        if ($beforeFrames -ne $afterFrames) { throw "Frame mismatch: $mission run $run" }
    }
    $baseMedian = Median $baseline
    $candidateMedian = Median $candidate
    $spread = (($baseline | Measure-Object -Maximum).Maximum - ($baseline | Measure-Object -Minimum).Minimum) / $baseMedian
    $loss = 1 - $candidateMedian / $baseMedian
    $performance = if ($spread -gt 0.05) { 'INCONCLUSIVE' } elseif ($loss -gt 0.05) { 'FAIL' } else { 'PASS' }
    $results += [pscustomobject]@{
        mission = $mission
        synthetic_baseline_medians = $baseline
        synthetic_candidate_medians = $candidate
        baseline_spread_percent = 100 * $spread
        throughput_change_percent = -100 * $loss
        throughput_gate = $performance
        real_baseline_seconds = $realBaseline
        real_candidate_seconds = $realCandidate
        sampled_peak_heap_baseline_bytes = $heapBaseline
        sampled_peak_heap_candidate_bytes = $heapCandidate
        sampled_source_alloc_baseline_bytes = $allocBaseline
        sampled_source_alloc_candidate_bytes = $allocCandidate
        sampled_source_alloc_median_change_bytes = (Median $allocCandidate) - (Median $allocBaseline)
        paired_frame_hashes = 'equal'
    }
}
$results | ConvertTo-Json -Depth 5
