#!/usr/bin/env bash
# Convenience launcher for the WebView app.
# Usage:
#   ./run.sh                          -> opens the default page
#   ./run.sh https://127.0.0.1:30141      -> opens the given URL
set -e

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"   # folder containing jdk/ and maven/
export JAVA_HOME="$BASE_DIR/jdk"
export PATH="$JAVA_HOME/bin:$BASE_DIR/maven/bin:$PATH"

cd "$BASE_DIR/webview-app"
exec mvn javafx:run ${1:+-DcommandlineArgs="$1"}
