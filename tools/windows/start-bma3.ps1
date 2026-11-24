param(
    [string] $ConfigRoot = (Join-Path $env:LOCALAPPDATA 'BotMosire-Alpha3'),
    [string] $Endpoint = 'ws://127.0.0.1:3001',
    [string] $Jar = '',
    [switch] $SaveToken,
    [switch] $NoToken
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$securityModule = Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1'
Import-Module $securityModule -ErrorAction Stop

if ($SaveToken -and $NoToken) { throw 'Choose either -SaveToken or -NoToken.' }
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ConfigRoot = [IO.Path]::GetFullPath($ConfigRoot).TrimEnd('\')
if (($ConfigRoot + '\').StartsWith(($repoRoot.TrimEnd('\') + '\'),
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep runtime data and credentials outside the source repository.'
}
if (-not $Jar) { $Jar = Join-Path $repoRoot 'bma3-app\target\bma3-app-0.0.9-fix2.jar' }
$Jar = [IO.Path]::GetFullPath($Jar)
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "BMA3 jar not found: $Jar. Build it first with .\mvnw.cmd -B -ntp package."
}
$uri = $null
if (-not [Uri]::TryCreate($Endpoint, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('ws', 'wss') -or -not $uri.Host -or $uri.UserInfo) {
    throw 'Endpoint must be a ws:// or wss:// URL without embedded credentials.'
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$created = -not (Test-Path -LiteralPath $ConfigRoot)
if ($created) {
    New-Item -ItemType Directory -Path $ConfigRoot -ErrorAction Stop | Out-Null
    $acl = Get-Acl -LiteralPath $ConfigRoot
    $acl.SetAccessRuleProtection($true, $false)
    $acl.SetOwner($identity)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $ConfigRoot -AclObject $acl
}
if (-not (Test-Path -LiteralPath $ConfigRoot -PathType Container) -or
        ((Get-Item -LiteralPath $ConfigRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw "Runtime root must be a normal directory: $ConfigRoot"
}
$acl = Get-Acl -LiteralPath $ConfigRoot
$owner = $acl.GetOwner([Security.Principal.SecurityIdentifier])
$rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
$foreignAllows = @($rules | Where-Object {
    $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -ne $identity.Value
})
$ownFullControl = @($rules | Where-Object {
    $_.AccessControlType -eq 'Allow' -and $_.IdentityReference.Value -eq $identity.Value -and
        ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq
            [Security.AccessControl.FileSystemRights]::FullControl -and
        ($_.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ContainerInherit) -and
        ($_.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ObjectInherit)
})
if (-not $acl.AreAccessRulesProtected -or $owner.Value -ne $identity.Value -or
        $foreignAllows.Count -gt 0 -or $ownFullControl.Count -eq 0) {
    throw "Runtime root is not private to the current user; choose a new directory: $ConfigRoot"
}

$configDir = Join-Path $ConfigRoot 'config'
$configFile = Join-Path $configDir 'app.yaml'
if (-not (Test-Path -LiteralPath $configFile)) {
    [IO.Directory]::CreateDirectory($configDir) | Out-Null
    $yamlEndpoint = $Endpoint.Replace("'", "''")
    $yamlTokenEnv = if ($NoToken) { '' } else { 'BMA3_ONEBOT_TOKEN' }
    $yaml = @"
# Local Windows runtime configuration. Existing configurations are never overwritten.
dataDir: ./data
server:
  host: 127.0.0.1
  port: 8765
  mcpPath: /mcp
  healthPort: 8766
qqconnect:
  accounts:
    - accountId: qq-main
      endpoint: '$yamlEndpoint'
      tokenEnv: '$yamlTokenEnv'
      enabled: true
inbox:
  httpPort: 0
xvfb:
  enabled: false
webconnect:
  enabled: false
watchdog:
  pluginHeartbeatEnabled: false
  pushBacklogEnabled: false
deployer:
  enabled: false
"@
    [IO.File]::WriteAllText($configFile, $yaml, [Text.UTF8Encoding]::new($false))
    Write-Host "Created runtime configuration: $configFile"
} else {
    Write-Host "Using existing configuration: $configFile"
    Write-Host 'Endpoint and -NoToken affect only newly created configurations; edit app.yaml for existing ones.'
}

$oldToken = [Environment]::GetEnvironmentVariable('BMA3_ONEBOT_TOKEN', 'Process')
$tokenFile = Join-Path $ConfigRoot 'onebot-token.dpapi'
try {
    if (-not $NoToken) {
        if ($PSVersionTable.PSVersion.Major -le 5) {
            Add-Type -AssemblyName System.Security
        } else {
            Add-Type -AssemblyName System.Security.Cryptography.ProtectedData
        }
        $token = $oldToken
        if (-not $token -and (Test-Path -LiteralPath $tokenFile -PathType Leaf)) {
            $plainBytes = [Security.Cryptography.ProtectedData]::Unprotect(
                    [IO.File]::ReadAllBytes($tokenFile), $null,
                    [Security.Cryptography.DataProtectionScope]::CurrentUser)
            try { $token = [Text.Encoding]::UTF8.GetString($plainBytes) }
            finally { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
        }
        if (-not $token) {
            $secure = Read-Host 'OneBot access token (input hidden)' -AsSecureString
            $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
            try { $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
            finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secure.Dispose() }
        }
        if ([string]::IsNullOrWhiteSpace($token)) {
            throw 'OneBot token is empty. For a provider without authentication use -NoToken on first setup.'
        }
        if ($SaveToken) {
            $plainBytes = [Text.Encoding]::UTF8.GetBytes($token)
            try {
                $encrypted = [Security.Cryptography.ProtectedData]::Protect($plainBytes, $null,
                        [Security.Cryptography.DataProtectionScope]::CurrentUser)
                [IO.File]::WriteAllBytes($tokenFile, $encrypted)
            } finally { [Array]::Clear($plainBytes, 0, $plainBytes.Length) }
            Write-Host 'Saved the OneBot token using Windows DPAPI for this Windows user.'
        }
        [Environment]::SetEnvironmentVariable('BMA3_ONEBOT_TOKEN', $token, 'Process')
        $token = $null
    }
    Write-Host "Starting BMA3; keep this window open. Runtime: $ConfigRoot"
    Push-Location $ConfigRoot
    try {
        & java -jar $Jar --config $ConfigRoot
        if ($LASTEXITCODE -ne 0) { throw "BMA3 exited with code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    [Environment]::SetEnvironmentVariable('BMA3_ONEBOT_TOKEN', $oldToken, 'Process')
}
