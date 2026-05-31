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

Args: `nodeId portOffset [lowmem|highmem] [free|members] storeid`

The port-offset controls both HTTP and game ports:
- HTTP port = `portOffset + 80`
- Game port = `portOffset + 43594`

| Script | HTTP port | Game port | portOffset |
|---|---|---|---|
| `run.bat` | 80 | 43594 | 0 |
| `run-8080.bat` | 8080 | 51594 | 8000 |
| `run-8888.bat` | 8888 | 52402 | 8808 |

If your server uses a non-standard split (e.g. HTTP 8080 but game 43594), edit `getCodeBase()` in `src/main/java/jagex2/client/Client.java`.

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
