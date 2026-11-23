param(
    [switch] $Rebuild,
    [string] $ProxyUri = '',
    [string] $SourceRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$agentlibVersion = '0.0.1-early2'
$artifactRoot = Join-Path $env:USERPROFILE '.m2\repository\io\github\c1589190\agentmosire'
$jar = Join-Path $artifactRoot "agentlib-mosire\$agentlibVersion\agentlib-mosire-$agentlibVersion.jar"
$pom = Join-Path $artifactRoot "agentlib-mosire\$agentlibVersion\agentlib-mosire-$agentlibVersion.pom"
$parent = Join-Path $artifactRoot "agent-mosire-parent\$agentlibVersion\agent-mosire-parent-$agentlibVersion.pom"

if (-not $Rebuild -and (Test-Path -LiteralPath $jar -PathType Leaf) -and
        (Test-Path -LiteralPath $pom -PathType Leaf) -and
        (Test-Path -LiteralPath $parent -PathType Leaf)) {
    Write-Host "AgentLib $agentlibVersion is already present in the Windows Maven cache."
    return
}

if (-not $SourceRoot) {
    throw "Specify -SourceRoot with the Windows path to the AgentMosire $agentlibVersion source checkout."
}

$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).ProviderPath
$sourcePom = Join-Path $SourceRoot 'pom.xml'
$modulePom = Join-Path $SourceRoot 'AgentLib\pom.xml'
$wrapper = Join-Path $SourceRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $sourcePom -PathType Leaf) -or
        -not (Test-Path -LiteralPath $modulePom -PathType Leaf) -or
        -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw 'The source directory must contain the AgentMosire parent POM, AgentLib module and Maven Wrapper.'
}
$sourceProject = [System.Xml.XmlDocument]::new()
$sourceProject.Load($sourcePom)
if ($sourceProject.project.groupId -ne 'io.github.c1589190.agentmosire' -or
        $sourceProject.project.artifactId -ne 'agent-mosire-parent' -or
        $sourceProject.project.version -ne $agentlibVersion) {
    throw "The source directory must contain io.github.c1589190.agentmosire:agent-mosire-parent:$agentlibVersion."
}

$oldMavenOpts = [Environment]::GetEnvironmentVariable('MAVEN_OPTS', 'Process')
try {
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

    Push-Location -LiteralPath $SourceRoot
    try {
        Write-Host "Installing AgentMosire parent POM and AgentLib $agentlibVersion..."
        & $wrapper -B -ntp -pl AgentLib -am '-DskipTests' '-Dspotbugs.skip=true' install
        if ($LASTEXITCODE -ne 0) { throw "AgentLib installation failed: $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    Write-Host "AgentLib $agentlibVersion is ready. Run BMA3 tests separately with .\mvnw.cmd test."
} finally {
    [Environment]::SetEnvironmentVariable('MAVEN_OPTS', $oldMavenOpts, 'Process')
}
