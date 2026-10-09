param(
    [string]$Destination = ""
)

$ErrorActionPreference = "Stop"

$ModuleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ModuleRoot "..")
$Maven = Join-Path $RepoRoot "mvnw.cmd"

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME must point to a JDK 21 installation."
}

$Java = Join-Path $env:JAVA_HOME "bin\java.exe"
$JPackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"

if (-not (Test-Path $Java) -or -not (Test-Path $JPackage)) {
    throw "JAVA_HOME must point to a full JDK 21 containing java.exe and jpackage.exe."
}

$VersionOutput = & $Java -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($VersionOutput -join [Environment]::NewLine) -notmatch 'version "21') {
    throw "Windows app-image packaging requires JDK 21."
}

$Target = Join-Path $ModuleRoot "target"
$InputDir = Join-Path $Target "jpackage-input"
$DefaultDestination = Join-Path $Target "jpackage"
$OutputDir = if ([string]::IsNullOrWhiteSpace($Destination)) {
    $DefaultDestination
} else {
    $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
}

Push-Location $RepoRoot
try {
    & $Maven -f "test-client\pom.xml" -DeventTiming.buildOrigin=local clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Engineering Client Maven package failed."
    }

    Remove-Item -Recurse -Force $InputDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $InputDir | Out-Null

    $dependencyArgs = @(
        "-f", "test-client\pom.xml",
        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies",
        "-DincludeScope=runtime",
        "-DoutputDirectory=$InputDir"
    )
    & $Maven @dependencyArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to collect Engineering Client runtime dependencies."
    }

    $MainJar = Get-ChildItem (Join-Path $Target "event-timing-engineering-client-*.jar") |
        Where-Object { $_.Name -notmatch '(-sources|-javadoc)\.jar$' } |
        Select-Object -First 1

    if (-not $MainJar) {
        throw "Engineering Client application JAR was not produced."
    }

    Copy-Item $MainJar.FullName (Join-Path $InputDir $MainJar.Name) -Force

    Remove-Item -Recurse -Force $OutputDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $OutputDir | Out-Null

    $jpackageArgs = @(
        "--type", "app-image",
        "--name", "EventTimingEngineeringClient",
        "--vendor", "brainboxemb",
        "--input", $InputDir,
        "--main-jar", $MainJar.Name,
        "--main-class", "io.github.brainboxemb.eventtiming.testclient.TestClientApplication",
        "--dest", $OutputDir
    )
    & $JPackage @jpackageArgs

    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed."
    }

    Write-Host "Engineering Client app-image:"
    Write-Host (Join-Path $OutputDir "EventTimingEngineeringClient")
}
finally {
    Pop-Location
}
