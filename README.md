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

The built JAR is written to:

```text
target/java-254-client.jar
```

The build adds the JARs from `lib/` to the application manifest, allowing
`target/java-254-client.jar` to be launched directly.

## Run

```bat
run.bat
```

Using `run.bat` is recommended because it supplies the Java options used by
LWJGL and starts the client with the default server settings.

You can also launch the packaged JAR directly:

```powershell
java -jar target/java-254-client.jar
```

## Distribution

The JAR contains the compiled client classes, so the `target/classes/` folder
is not needed. The dependency JARs are separate and must be distributed with
the client:

```text
Java-254-Client/
|-- lib/
|   `-- *.jar
`-- target/
    `-- java-254-client.jar
```

Keep the `lib/` and `target/` folders in these relative locations. Moving the
JAR out of `target/` will prevent it from finding the libraries in `lib/`.
