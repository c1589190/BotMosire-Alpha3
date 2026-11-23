param([string[]] $MavenArgs = @())

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not [OperatingSystem]::IsWindows() -or [Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture -ne 'X64') {
    throw 'The managed app-image build requires Windows x64 and PowerShell 7.'
}

function Invoke-BuildCommand {
    param([string] $Program, [string[]] $Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE."
    }
}

$root = Split-Path -Parent $PSScriptRoot
$plugin = Join-Path $root 'dsh-plugin'
$version = (Get-Content -LiteralPath (Join-Path $plugin 'package.json') -Raw | ConvertFrom-Json).version
$dist = Join-Path $root 'dist'
$temporaryRoot = (Resolve-Path -LiteralPath ([IO.Path]::GetTempPath())).ProviderPath.TrimEnd('\')
$build = Join-Path $temporaryRoot ('bma3-dsh-build-' + [guid]::NewGuid().ToString('N'))
$inputDir = Join-Path $build 'jpackage-input'
$runtime = Join-Path $build 'java-runtime'
$imageDir = Join-Path $build 'app-image'
$stage = Join-Path $build 'bundle'
$payload = Join-Path $stage 'runtime'
$tar = Join-Path $env:SystemRoot 'System32\tar.exe'
$modules = 'java.base,java.compiler,java.desktop,java.management,java.naming,java.net.http,java.rmi,java.scripting,java.sql,java.logging,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.unsupported'

Push-Location -LiteralPath $root
try {
    New-Item -ItemType Directory -Path $inputDir, $imageDir, $payload, $dist -Force | Out-Null
    Invoke-BuildCommand '.\mvnw.cmd' (@('-B', '-ntp') + $MavenArgs + @('-pl', 'bma3-app', '-am', 'package', '-DskipTests'))
    Copy-Item -LiteralPath (Join-Path $root "bma3-app\target\bma3-app-$version.jar") -Destination (Join-Path $inputDir 'app.jar')

    Invoke-BuildCommand 'jlink.exe' @(
        '--add-modules', $modules, '--output', $runtime,
        '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=2'
    )
    Invoke-BuildCommand 'jpackage.exe' @(
        '--type', 'app-image', '--name', 'botmosire-a3', '--input', $inputDir,
        '--main-jar', 'app.jar', '--main-class', 'io.github.c1589190.bma3.app.Main',
        '--runtime-image', $runtime, '--java-options', '-Dfile.encoding=UTF-8',
        '--win-console', '--dest', $imageDir
    )
    Invoke-BuildCommand $tar @('-C', $imageDir, '-czf', (Join-Path $payload 'image.tar.gz'), 'botmosire-a3')

    $files = 'index.js', 'lib', 'examples', 'cordis.patch.yml', 'README.md', 'package.json'
    Copy-Item -LiteralPath ($files | ForEach-Object { Join-Path $plugin $_ }) -Destination $stage -Recurse
    Invoke-BuildCommand 'node.exe' @(
        (Join-Path $PSScriptRoot 'stage-dsh-bundle.mjs'), $stage,
        (Join-Path $imageDir 'botmosire-a3\botmosire-a3.exe')
    )
    $packed = Invoke-BuildCommand 'npm.cmd' @('pack', '--ignore-scripts', '--json', '--pack-destination', $build, $stage)
    $npmFile = ($packed | ConvertFrom-Json -NoEnumerate)[0].filename
    $fileName = "mosire-bma3-dsh-managed-$version-windows-x64.tgz"
    $bundle = Join-Path $dist $fileName
    Move-Item -LiteralPath (Join-Path $build $npmFile) -Destination $bundle -Force
    $sha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$bundle.sha256", "$sha256  $fileName`n", [Text.Encoding]::ASCII)
    Write-Output $bundle
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $build -PathType Container) {
        $resolved = (Resolve-Path -LiteralPath $build).ProviderPath
        if ((Split-Path -Parent $resolved) -eq $temporaryRoot -and (Split-Path -Leaf $resolved) -like 'bma3-dsh-build-*') {
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}
