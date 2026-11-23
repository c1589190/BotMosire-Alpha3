param(
    [switch] $Rebuild,
    [string] $ProxyUri = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentlibVersion = '0.2.8'
$revision = '5435f94d1b3f3a6e5785b66c289ec032f59d8d0a'
$expectedHash = 'e39985860be4b3cdbacaa0d26b8d4bc9da2774dfc91a838a77ab3c6ec464c6ef'
$sourceRoot = Join-Path $repoRoot "build\dependencies\projectmosire-$($revision.Substring(0, 12))"
$artifactRoot = Join-Path $env:USERPROFILE '.m2\repository\io\mosire'
$jar = Join-Path $artifactRoot "agentlib-mosire\$agentlibVersion\agentlib-mosire-$agentlibVersion.jar"
$pom = Join-Path $artifactRoot "agentlib-mosire\$agentlibVersion\agentlib-mosire-$agentlibVersion.pom"
$parent = Join-Path $artifactRoot "mosire-parent\$agentlibVersion\mosire-parent-$agentlibVersion.pom"

if (-not $Rebuild -and (Test-Path -LiteralPath $jar) -and
        (Test-Path -LiteralPath $pom) -and (Test-Path -LiteralPath $parent)) {
    Write-Host "AgentLib $agentlibVersion is already present in the Windows Maven cache."
    Write-Host 'Use -Rebuild to reinstall it from the pinned public source.'
    return
}

$oldMavenOpts = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
if ($ProxyUri) {
    $proxy = [Uri]$ProxyUri
    if ($proxy.Scheme -notin @('http', 'https') -or -not $proxy.Host -or
            $proxy.Port -lt 1 -or $proxy.UserInfo) {
        throw 'ProxyUri must be an HTTP(S) proxy without embedded credentials.'
    }
    $proxyOptions = "-Dhttp.proxyHost=$($proxy.Host) -Dhttp.proxyPort=$($proxy.Port) " +
            "-Dhttps.proxyHost=$($proxy.Host) -Dhttps.proxyPort=$($proxy.Port)"
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', "$oldMavenOpts $proxyOptions", 'Process')
}

try {
    Write-Host "Downloading ProjectMosire source at $revision..."
    $client = [Net.WebClient]::new()
    $client.Headers.Add('User-Agent', 'bma3-windows-bootstrap')
    if ($ProxyUri) { $client.Proxy = [Net.WebProxy]::new($ProxyUri) }
    try {
        $archiveBytes = $client.DownloadData(
                "https://api.github.com/repos/c1589190/ProjectMosire/zipball/$revision")
    } finally {
        $client.Dispose()
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $actualHash = ([BitConverter]::ToString($sha.ComputeHash($archiveBytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
    if ($actualHash -ne $expectedHash) {
        throw 'Pinned ProjectMosire archive checksum does not match; no source was extracted.'
    }

    Add-Type -AssemblyName System.IO.Compression
    $root = [IO.Path]::GetFullPath($sourceRoot).TrimEnd('\') + '\'
    $stream = [IO.MemoryStream]::new($archiveBytes)
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        foreach ($entry in $archive.Entries) {
            $separator = $entry.FullName.IndexOf('/')
            if ($separator -lt 0) { continue }
            $relative = $entry.FullName.Substring($separator + 1)
            if ($relative.EndsWith('/')) { continue }
            if (-not ($relative -eq 'pom.xml' -or $relative -eq 'mvnw.cmd' -or
                    $relative -eq 'LICENSE' -or $relative -eq 'NOTICE' -or
                    $relative -like '.mvn/*' -or $relative -like 'config/*' -or
                    $relative -like 'AgentLibMosire/*')) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $sourceRoot $relative))
            if (-not $target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'The source archive contains an unsafe path.'
            }
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
            $input = $entry.Open()
            try {
                if (Test-Path -LiteralPath $target) {
                    $existing = [IO.File]::ReadAllBytes($target)
                    $copy = [IO.MemoryStream]::new()
                    try { $input.CopyTo($copy); $incoming = $copy.ToArray() } finally { $copy.Dispose() }
                    if ([Convert]::ToBase64String($existing) -ne [Convert]::ToBase64String($incoming)) {
                        throw "Pinned dependency source differs from the archive: $target"
                    }
                } else {
                    $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew)
                    try { $input.CopyTo($output) } finally { $output.Dispose() }
                }
            } finally {
                $input.Dispose()
            }
        }
    } finally {
        $archive.Dispose()
        $stream.Dispose()
    }

    $wrapper = Join-Path $sourceRoot 'mvnw.cmd'
    Push-Location $sourceRoot
    try {
        Write-Host 'Installing the upstream parent POM in the Windows Maven cache...'
        & $wrapper -B -ntp -N '-DskipTests' install
        if ($LASTEXITCODE -ne 0) { throw "Parent POM installation failed: $LASTEXITCODE" }
        Write-Host 'Building the upstream AgentLib dependency (upstream tests are not run)...'
        & $wrapper -B -ntp -f 'AgentLibMosire/pom.xml' '-Dmaven.test.skip=true' '-Dspotbugs.skip=true' install
        if ($LASTEXITCODE -ne 0) { throw "AgentLib installation failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Write-Host "AgentLib $agentlibVersion is ready. Run BMA3 tests separately with .\mvnw.cmd test."
} finally {
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $oldMavenOpts, 'Process')
}
