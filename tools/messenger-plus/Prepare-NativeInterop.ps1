param(
    [string]$ScriptDirectory = 'C:\Program Files (x86)\Yuna Software\Messenger Plus!\WLM-Scripts\Msngr Compatibility Probe',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\app\build\plus-interop')
)
$ErrorActionPreference = 'Stop'
$cases = @()
foreach ($mask in 0..15) {
    foreach ($foreground in 0..3) {
        foreach ($background in 0..3) {
            $features = @()
            for ($bit = 0; $bit -lt 4; $bit++) {
                if ($mask -band (1 -shl $bit)) { $features += @('b', 'i', 'u', 's')[$bit] }
            }
            if ($foreground -band 1) { $features += 'c' }
            if ($foreground -band 2) { $features += 'C' }
            if ($background -band 1) { $features += 'a' }
            if ($background -band 2) { $features += 'A' }
            $source = 'AbçZ'
            for ($index = $features.Count - 1; $index -ge 0; $index--) {
                switch -CaseSensitive ($features[$index]) {
                    'c' { $source = "[c=#C00000]$source[/c]" }
                    'C' { $source = "[c=#FF0000]$source[/c=#0000FF]" }
                    'a' { $source = "[a=#FFFF80]$source[/a]" }
                    'A' { $source = "[a=#FFFF00]$source[/a=#00FF00]" }
                    default { $tag = $features[$index]; $source = "[$tag]$source[/$tag]" }
                }
            }
            $cases += @{ id = ('{0:00}{1}{2}' -f $mask, $foreground, $background); source = $source }
        }
    }
}
ConvertTo-Json -InputObject $cases | Set-Content -LiteralPath (Join-Path $ScriptDirectory 'interop-cases.json') -Encoding Unicode
$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$batches = @()
for ($index = 0; $index -lt $cases.Count; $index += 8) {
    $batches += (($cases[$index..($index + 7)] | ForEach-Object { 'PLUS-RX-' + $_.id + ' ' + $_.source }) -join ' | ')
}
ConvertTo-Json -InputObject $batches | Set-Content -LiteralPath (Join-Path $OutputDirectory 'ui-batches.json') -Encoding utf8
$originalScript = Join-Path $ScriptDirectory 'Probe.js'
$backupScript = Join-Path $ScriptDirectory 'Probe.js.before-interop'
if ((Test-Path -LiteralPath $originalScript) -and !(Test-Path -LiteralPath $backupScript)) {
    Copy-Item -LiteralPath $originalScript -Destination $backupScript
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'NativeInteropProbe.js') -Destination (Join-Path $ScriptDirectory 'Probe.js')
Write-Output "Prepared $($cases.Count) synthetic format subsets. Reload the installed probe in Plus! before running the opt-in Android test."
