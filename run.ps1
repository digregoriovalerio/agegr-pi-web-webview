# Convenience launcher for the WebView app (PowerShell).
# Usage:
#   ./run.ps1                          -> opens the default page
#   ./run.ps1 https://127.0.0.1:30141      -> opens the given URL

param(
    [Parameter(Position = 0)]
    [string]$Url
)

$ErrorActionPreference = 'Stop'

$BASE_DIR = $PSScriptRoot   # folder containing jdk/ and maven/
$env:JAVA_HOME = Join-Path $BASE_DIR 'jdk'
$env:PATH = "$($env:JAVA_HOME)\bin;$BASE_DIR\maven\bin;$env:PATH"

Set-Location -Path (Join-Path $BASE_DIR 'webview-app')

$mvnArgs = @('javafx:run')
if ($PSBoundParameters.ContainsKey('Url')) {
    $mvnArgs += "-DcommandlineArgs=$Url"
}

& mvn @mvnArgs
cd ..
