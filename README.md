# Java 254 Client

Full RS2 build-254 client ported into this project. All 74 original source files are included under `src/main/java/jagex2/`, `sign/`, and `deob/`.

## Build

```bat
build.bat
```

Or with PowerShell:

```powershell
.\build.ps1
```

Requires JDK 17+.

For a standalone JAR containing the required libraries, use Maven:

```powershell
mvn clean package
```

The standalone JAR is written to `target/`.

## Run

```bat
run.bat
```

Using `run.bat` is recommended because it supplies the Java options used by
LWJGL and starts the client with the default server settings.

You can also launch a standalone Maven or GitHub Release JAR directly:

```powershell
java -jar java-254-client.jar
```
