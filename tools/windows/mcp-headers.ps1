param(
    [string] $ConfigRoot = (Join-Path $env:LOCALAPPDATA 'BotMosire-Alpha3')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$envFile = Join-Path $ConfigRoot '.bma3env'
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "BMA3 token file is not available: $envFile. Start BMA3 first."
}
$entries = @(Get-Content -LiteralPath $envFile -Encoding UTF8 | Where-Object { $_ -match '^MCP_TOKEN=' })
if ($entries.Count -ne 1 -or $entries[0] -notmatch '^MCP_TOKEN=([a-f0-9]{48})$') {
    throw 'BMA3 token file does not contain a valid, unambiguous MCP_TOKEN.'
}
$headers = @{ Authorization = 'Bearer ' + $Matches[1] }
[Console]::Out.WriteLine((ConvertTo-Json -InputObject $headers -Compress))
