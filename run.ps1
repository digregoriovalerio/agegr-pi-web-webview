#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the JavaFX WebView application.

.DESCRIPTION
    Sets up JAVA_HOME and MAVEN_HOME, launches the webview-app via Maven,
    and restores the original working directory on exit.

    This is the ONLY supported way to run the application. Running
    `mvn javafx:run` directly bypasses JVM argument setup, the pre-build
    compilation step, the pi-web health check, and working-directory
    restoration, and will fail or behave incorrectly.

.PARAMETER SkipBuild
    Skips the Maven compile step (faster iteration when code hasn't changed).

.PARAMETER LogLevel
    Sets the application log level at startup via the -Dpi.log.level JVM
    argument. Accepted values: TRACE, DEBUG, INFO, WARN, ERROR.
    Default: DEBUG (shows all inter-component communication).

.EXAMPLE
    ./run.ps1
    Starts the app with DEBUG logging.

.EXAMPLE
    ./run.ps1 -LogLevel WARN
    Starts the app with WARN logging (less noise).

.EXAMPLE
    ./run.ps1 -SkipBuild -LogLevel ERROR
    Skips compilation and shows only errors.
#>
param(
    [switch]$SkipBuild,
    [ValidateSet("TRACE", "DEBUG", "INFO", "WARN", "ERROR")]
    [string]$LogLevel = "WARN"
)

$ErrorActionPreference = "Stop"
$originalCwd = Get-Location
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$javaHome = Join-Path $scriptDir "jdk"
$mavenHome = Join-Path $scriptDir "maven"
$webviewAppDir = Join-Path $scriptDir "webview-app"

if (-not (Test-Path (Join-Path $javaHome "bin/java.exe"))) { Write-Error "JDK not found at $javaHome"; exit 1 }
if (-not (Test-Path (Join-Path $mavenHome "bin/mvn.cmd"))) { Write-Error "Maven not found at $mavenHome"; exit 1 }

$env:JAVA_HOME = $javaHome
$env:MAVEN_HOME = $mavenHome
$env:PATH = "$javaHome\bin;$mavenHome\bin;$env:PATH"

Write-Host "Starting JavaFX WebView app..." -ForegroundColor Cyan

try {
    Push-Location $webviewAppDir
    if (-not $SkipBuild) {
        Write-Host "Compiling..." -ForegroundColor Yellow
        & "$mavenHome\bin/mvn.cmd" compile -q
        if ($LASTEXITCODE -ne 0) { Write-Error "Compilation failed"; exit $LASTEXITCODE }
    }

    # Pass the log level as a JVM argument so AppLogger picks it up.
    # The JavaFX Maven plugin ignores -D flags unless passed via jvmArguments.
    & "$mavenHome\bin/mvn.cmd" javafx:run "-Djavafx.run.jvmArguments=-Dpi.log.level=$LogLevel"
}
finally { Pop-Location; Set-Location $originalCwd }

Write-Host "Application stopped." -ForegroundColor Cyan
