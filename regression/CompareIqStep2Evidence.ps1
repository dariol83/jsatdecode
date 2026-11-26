param(
    [Parameter(Mandatory=$true)][string]$Baseline,
    [Parameter(Mandatory=$true)][string]$Candidate
)

$ErrorActionPreference = 'Stop'

function Read-Fields([string]$log, [string]$prefix) {
    $line = @(Get-Content -LiteralPath $log | Where-Object { $_.StartsWith($prefix + '={') })
    if ($line.Count -ne 1) { throw "Missing or duplicate $prefix in $log" }
    $body = $line[0].Substring($prefix.Length + 2).TrimEnd('}')
    $fields = @{}
    foreach ($entry in ($body -split ', ')) {
        $parts = $entry -split '=', 2
        if ($parts.Count -ne 2) { throw "Invalid metric: $entry" }
        if ($parts[0] -notin @('source.elapsed_s', 'source.realtime_factor')) {
            $fields[$parts[0]] = $parts[1]
        }
    }
    return $fields
}

function Read-Hash([string]$log, [string]$key) {
    $line = @(Get-Content -LiteralPath $log | Where-Object { $_.StartsWith($key + '=') })
    if ($line.Count -ne 1) { throw "Missing or duplicate $key in $log" }
    return $line[0].Substring($key.Length + 1)
}

function Assert-SameBytes([string]$left, [string]$right) {
    $before = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $left).Path)
    $after = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $right).Path)
    if ($before.Length -ne $after.Length) { throw "File lengths differ: $left and $right" }
    for ($index = 0; $index -lt $before.Length; $index++) {
        if ($before[$index] -ne $after[$index]) { throw "Files differ at byte ${index}: $left and $right" }
    }
}

$results = @()
foreach ($mission in @('hinode', 'elektro')) {
    $oldLog = Join-Path $Baseline "baseline-$mission-detail.log"
    $newLog = Join-Path $Candidate "final-$mission-detail.log"
    $checks = [ordered]@{}
    foreach ($key in @('iq_sha256', 'frame_payload_sha256', 'block_metadata_sha256', 'frame_metadata_sha256')) {
        $expected = Read-Hash $oldLog $key
        $actual = Read-Hash $newLog $key
        if ($expected -cne $actual) { throw "$mission $key mismatch: $expected != $actual" }
        $checks[$key] = $actual
    }
    foreach ($kind in @('source_metrics', 'decoder_metrics')) {
        $before = Read-Fields $oldLog $kind
        $after = Read-Fields $newLog $kind
        if ($before.Count -ne $after.Count) { throw "$mission $kind has different field counts" }
        foreach ($key in $before.Keys) {
            if (-not $after.ContainsKey($key) -or $before[$key] -cne $after[$key]) {
                throw "$mission $kind mismatch for $key"
            }
        }
        $checks[$kind] = 'equal'
    }
    foreach ($kind in @('blocks', 'frame-metadata')) {
        $oldFile = Join-Path $Baseline "baseline-$mission-detail-$kind.bin"
        $newFile = Join-Path $Candidate "final-$mission-detail-$kind.bin"
        Assert-SameBytes $oldFile $newFile
        $before = (Get-FileHash -Algorithm SHA256 -LiteralPath $oldFile).Hash
        $after = (Get-FileHash -Algorithm SHA256 -LiteralPath $newFile).Hash
        if ($before -ne $after) { throw "$mission $kind file mismatch" }
    }
    $oldFrames = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Baseline "baseline-$mission-frames.bin")).Hash
    $newFrames = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $Candidate "runtime-$mission-frames.bin")).Hash
    Assert-SameBytes (Join-Path $Baseline "baseline-$mission-frames.bin") (Join-Path $Candidate "runtime-$mission-frames.bin")
    if ($oldFrames -ne $newFrames) { throw "$mission runtime frame file mismatch" }
    $checks['runtime_frame_sha256'] = $newFrames.ToLowerInvariant()
    $results += [pscustomobject]@{ mission = $mission; checks = $checks }
}
$results | ConvertTo-Json -Depth 5
