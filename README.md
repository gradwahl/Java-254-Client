# Java 254 Client

Full RS2 build-254 client With Native OpenGL-lwjgl

## Build

```bat
build.bat
```

Or with PowerShell:

```powershell
.\build.ps1
```

Requires JDK 17+.

The generated `target/Progressive-Java-Client.jar` is standalone: it contains the
required libraries and native binaries, and can be copied elsewhere by itself.

You can also build with Maven:

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

You can also launch the generated JAR or a GitHub Release JAR directly:

```powershell
java -jar Progressive-Java-Client.jar
```
