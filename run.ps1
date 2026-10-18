#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the JavaFX WebView application.
.DESCRIPTION
    Sets up JAVA_HOME and MAVEN_HOME, launches the webview-app via Maven,
    and restores the original working directory on exit.
#>
param(
    [switch]$SkipBuild,
    [ValidateSet("TRACE", "DEBUG", "INFO", "WARN", "ERROR")]
    [string]$LogLevel = "DEBUG"
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

    & "$mavenHome\bin/mvn.cmd" javafx:run "-Djavafx.run.jvmArguments=-Dpi.log.level=$LogLevel"
}
finally { Pop-Location; Set-Location $originalCwd }

Write-Host "Application stopped." -ForegroundColor Cyan