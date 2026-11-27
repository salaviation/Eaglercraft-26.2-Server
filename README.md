# Eaglercraft 26.2 

This ZIP lets you run Eaglercraft 26.2 locally. 
The first launch will download the required server files.

## Requirements

Before starting, make sure you have:

- **Node.js 20 or newer** (with npm)
- **Java 25 JDK**
- A strong internet connection for the first setup
- Around 3 GB of available RAM for the server

Downloads:
- [Node.js](https://nodejs.org/)
- [Eclipse Temurin JDK 25](https://adoptium.net/temurin/releases/?version=25)

## macOS Setup

1. Extract the ZIP file somewhere on your Mac.
2. Install Node.js and Java 25 if you haven't already.
3. Double-click `START-MACOS.command` to launch the setup.
4. If macOS blocks the script, control-click it and select **Open**.
5. Read and accept the Minecraft EULA when prompted in Terminal.

The launcher will download Paper, Velocity, EaglerXServer, ViaVersion, and ViaBackwards automatically.

You can also start it manually through Terminal:

```sh
npm install
npm start
```

## Windows Setup

1. Extract the ZIP file into a folder.
2. Make sure Node.js 20+ and Java 25 are installed.
3. Double-click `START-WINDOWS.bat`.
4. Follow the instructions in Command Prompt and accept the EULA if you agree to it.

The launcher will handle the rest.

## Connecting to the Server

Once everything starts, open your browser and visit:

**Game client:** http://localhost:3001

**Multiplayer server:** `ws://localhost:25557/`

**Paper backend:** `127.0.0.1:25566`

The local multiplayer server should already be added to your server list.

Keep the Terminal or Command Prompt window open while playing. Closing it will stop the server.

If you want friends to connect over the internet, you'll need to host the web client and WebSocket endpoint publicly. Update `publicWebSocketUrl` in `config.json` with your public `wss://` address.

## Configuration

Most server settings can be changed in `config.json`, including:

- Server name
- Game mode
- Maximum players
- Server ports

Survival mode is enabled by default.

## Stopping the Server

To shut everything down, press `Ctrl+C` in the launcher window.

If something isn't working, run:

```sh
npm run doctor
```

This checks your local setup for common problems.

The server files are downloaded into the `runtime/` folder during the first setup. They aren't included in the ZIP.

The Eaglercraft client and world files are already included, so you won't need to download those separately.

## Eaglercraft Enhancements

The server includes an additional Paper plugin for authentication, a protected login room,
spawn sanctuaries, and a live action-bar HUD.

### Administrator commands

- `/auth enable` - enable authentication
- `/auth disable` - disable authentication without deleting registrations
- `/auth status` - show authentication state
- `/auth reset <player>` - require the player to register again
- `/spawnprotect` - toggle the default 11-block spawn sanctuary
- `/sanctuary r <radius>` - create an overlapping-safe sanctuary centred on the OP
- `/removesanctuary` - remove every sanctuary containing the OP, including the default spawn sanctuary

### Player commands

- `/register <password> <confirm-password>` - register for the first time
- `/login <password>` - authenticate after joining
- `/changepassword <old-password> <new-password>` - replace an authentication password

Authentication is enabled by default. Passwords are hashed with salted Argon2id parameters
and account files are stored below `runtime/backend/plugins/EaglerEnhancements/accounts/`.
The server is in offline/insecure mode, so accounts are bound to the Paper UUID and exact
player name. Offline mode cannot cryptographically prove that a username is owned by its
legitimate player; restrict access to trusted players and use a whitelist/firewall if needed.

The login room is protected at and around netherite floor block `(0,199,0)`, with player
space centred on `(0.5,200,0.5)`. Authenticated players are restored to their saved previous
location or the normal spawn.

The plugin uses server-side Paper events for PVP, mob spawning/movement, explosions,
block modification, inventory and interaction restrictions. The default sanctuary is a
hemisphere with radius 11 centred on the overworld spawn and extending upward.

### Custom totems

Fill a 3x3 crafting grid with nine Totems of Undying to craft an aqua-named **Shard of Life**.
Fill a 3x3 grid with nine Shards of Life to craft the enchanted, light-purple **Heart of
Undying**. Both items are Totems of Undying and retain their normal totem behavior. Right-click
with a Heart of Undying to consume it, gain one extra heart, and restore two health points.
The extra heart persists through relogging and server restarts, but is reset to the base 20.0
maximum health on death.

### HUD and F3 limitation

The server sends `Time:`, `Biome:` and `XYZ:` to the Eagler player through an action bar. The current
bundled client is a 73 MB generated HTML/WebAssembly client and contains no inspectable
JavaScript source hook for F3 state. Therefore, this project can provide the HUD and normal
F3 key behaviour, but it cannot reliably hide only the action-bar HUD when F3 is pressed
or position it beside the FPS counter without an available client source hook. This is the
remaining client-integration limitation, not a server plugin omission.

The same bundled-client limitation prevents adding an Elytra-only slot to the inventory screen
or rendering both chestplate armor and Elytra on a player at once. Those changes require editable
client source and a rebuilt client; a server plugin alone cannot change those client visuals.

### Build and restart

```sh
npm run build:plugin
npm start
```

`npm start` rebuilds and installs the plugin automatically. Press `Ctrl+C` to stop Paper and
Velocity; the launcher sends their graceful `stop`/`shutdown` commands first. Codespaces
controls whether a codespace remains active and does not allow application code to bypass
GitHub inactivity or lifecycle restrictions.
