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

## Run

```bat
run.bat
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
