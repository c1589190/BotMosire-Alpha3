param(
    [string] $ConfigRoot = (Join-Path $env:LOCALAPPDATA 'BotMosire-Alpha3'),
    [ValidateRange(1, 65535)] [int] $Port = 8765,
    [ValidatePattern('^[A-Za-z0-9_-]+$')] [string] $Name = 'bma3'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$helper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'mcp-headers.ps1'))
$ConfigRoot = [IO.Path]::GetFullPath($ConfigRoot)
if (-not (Test-Path -LiteralPath (Join-Path $ConfigRoot '.bma3env') -PathType Leaf)) {
    throw 'Start BMA3 first so it can create the protected MCP token file.'
}
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { throw 'Codex CLI was not found in PATH.' }
$json = & codex mcp list --json
if ($LASTEXITCODE -ne 0) { throw 'Could not read the existing Codex MCP configuration.' }
$existing = @($json | ConvertFrom-Json | Where-Object { $_.name -eq $Name })
if ($existing.Count -gt 0) { throw "Codex MCP server '$Name' already exists; its configuration was not changed." }

$codexRoot = [Environment]::GetEnvironmentVariable('CODEX_HOME', 'Process')
if (-not $codexRoot) { $codexRoot = Join-Path $env:USERPROFILE '.codex' }
[IO.Directory]::CreateDirectory($codexRoot) | Out-Null
$configFile = Join-Path $codexRoot 'config.toml'
$command = 'powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
        $helper + '" -ConfigRoot "' + $ConfigRoot + '"'
$commandToml = ConvertTo-Json -InputObject $command -Compress
$block = "`n[mcp_servers.$Name]`nurl = `"http://127.0.0.1:$Port/mcp`"`n" +
        "http_headers_helper = $commandToml`n"
[IO.File]::AppendAllText($configFile, $block, [Text.UTF8Encoding]::new($false))
Write-Host "Added standard HTTP MCP server '$Name' to $configFile."
Write-Host 'The configuration contains a token-file helper, not the token. Open a new Codex session to connect.'
