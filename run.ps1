#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the JavaFX WebView application.
.DESCRIPTION
    Sets up JAVA_HOME and MAVEN_HOME, launches the webview-app via Maven,
    and restores the original working directory on exit.

.PARAMETER LogLevel
    Sets the application log level (TRACE, DEBUG, INFO, WARN, ERROR).
    Default is DEBUG so you can see all directional communication between
    components. Use INFO for normal operation, WARN/ERROR to reduce noise.
#>
param(
    [ValidateSet("TRACE", "DEBUG", "INFO", "WARN", "ERROR")]
    [string]$LogLevel = "DEBUG",

    [switch]$NoOpen
)

$ErrorActionPreference = "Stop"

# Remember original working directory
$originalCwd = Get-Location

# Set up paths relative to this script location
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$javaHome = Join-Path $scriptDir "jdk"
$mavenHome = Join-Path $scriptDir "maven"
$webviewAppDir = Join-Path $scriptDir "webview-app"

# Validate JDK exists
if (-not (Test-Path (Join-Path $javaHome "bin/java.exe"))) {
    Write-Error "JDK not found at $javaHome"
    exit 1
}

# Validate Maven exists
if (-not (Test-Path (Join-Path $mavenHome "bin/mvn.cmd"))) {
    Write-Error "Maven not found at $mavenHome"
    exit 1
}

# Set environment
$env:JAVA_HOME = $javaHome
$env:MAVEN_HOME = $mavenHome
$env:PATH = "$javaHome\bin;$mavenHome\bin;$env:PATH"

# Build JVM arguments for the JavaFX app: log level + autohello enabled by default
$jvmArgs = "-Dpi.log.level=$LogLevel -Dpi.autohello.enabled=true"

Write-Host "Starting JavaFX WebView app..." -ForegroundColor Cyan
Write-Host "JAVA_HOME=$javaHome" -ForegroundColor Gray
Write-Host "MAVEN_HOME=$mavenHome" -ForegroundColor Gray
Write-Host "Working directory: $webviewAppDir" -ForegroundColor Gray
Write-Host "Log level: $LogLevel" -ForegroundColor Gray
Write-Host "JVM args: $jvmArgs" -ForegroundColor Gray

try {
    Push-Location $webviewAppDir
    & "$mavenHome\bin/mvn.cmd" javafx:run "-Djavafx.run.jvmArguments=$jvmArgs"
}
finally {
    Pop-Location
    # Reset cwd to the original directory
    Set-Location $originalCwd
}

Write-Host "Application stopped." -ForegroundColor Cyan
