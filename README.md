# JavaFX WebView App

A minimal browser built with Java 21 + Maven + JavaFX `WebView` (WebKit).
It opens a URL provided as input, either from the **command line** or typed into the
**address bar** at runtime.

## Layout

```
jww/
├── jdk/                    # bundled JDK 21 (not on PATH by default)
├── maven/                  # bundled Maven 3.9.16
├── run.sh                  # convenience launcher
└── webview-app/            # the Maven project
    ├── pom.xml             # JavaFX 21.0.1, auto-selects native classifier per OS/arch
    └── src/main/java/com/example/webview/WebViewApp.java
```

## Run

Option 1 — convenience script:

```bash
./run.sh                       # default page
./run.sh https://127.0.0.1:30141   # with a URL as input
```

Option 2 — plain Maven (from `jww/`):

```bash
export JAVA_HOME=$PWD/jdk
export PATH=$JAVA_HOME/bin:$PWD/maven/bin:$PATH
cd webview-app
mvn javafx:run
mvn javafx:run -DcommandlineArgs="https://127.0.0.1:30141"
```

Option 3 — direct java (no Maven):

```bash
M2=~/.m2/repository/org/openjfx
CP="$M2/javafx-base/21.0.1/javafx-base-21.0.1-linux-aarch64.jar:\
$M2/javafx-graphics/21.0.1/javafx-graphics-21.0.1-linux-aarch64.jar:\
$M2/javafx-controls/21.0.1/javafx-controls-21.0.1-linux-aarch64.jar:\
$M2/javafx-media/21.0.1/javafx-media-21.0.1-linux-aarch64.jar:\
$M2/javafx-web/21.0.1/javafx-web-21.0.1-linux-aarch64.jar"
jdk/bin/java --module-path "$CP" --add-modules javafx.controls,javafx.web \
  -cp webview-app/target/classes com.example.webview.WebViewApp https://127.0.0.1:30141
```

## Features

- URL input via command-line argument **and** address bar (Enter to navigate)
- Back / Forward / Reload buttons
- Address bar follows links clicked inside the page
- Auto-prepends `https://` when the scheme is omitted (e.g. `127.0.0.1:30141`)

## Build only

```bash
cd webview-app && mvn package
```
