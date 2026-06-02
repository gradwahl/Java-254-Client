# Progressive Java Client

Early Java desktop client for a LostCityRS / 2004SP revision 254+ servers.

## Current status

Implemented so far:

- Revision `254` configuration
- Swing desktop window
- 765x503 game canvas
- 50 TPS game loop
- WebSocket connection to the game server
- `/crc` cache checksum loading
- 254 login handshake using opcodes `14`, `16`, and `18`
- ISAAC cipher setup
- Client/server protocol constants
- Test login UI

Not implemented yet:

Not implemented yet

Planned features and major work still to be done:

- 117HD-style rendering port
- HD terrain lighting and shading
- HD textures and normal maps
- Water, lava, and animated surface effects
- Improved skybox/fog/atmosphere rendering
- Modern GPU-based scene rendering
- 60 FPS animation/camera support while keeping server TPS compatible
- True resizable and fullscreen client modes
- Original 765x503 fixed-mode compatibility

## Requirements

- Java 17+
- Maven
- A compatible LostCity/2004Scape revision 254 server running locally or remotely (Progressive strongly advised)

## Build

```bash
mvn package
