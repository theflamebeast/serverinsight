Client-side Fabric mod that tells you everything your client can work out about the server
you're on — where it's hosted, what software it runs, which plugins and mods it has, and how
well it's ticking. One command, no server-side install.

## Links
- Modrinth: https://modrinth.com/mod/server-insight
- Support: https://paypal.me/theflamebeast

## Command
- `/serverinsight` — Sends all details of the server in an organized structure

## Features
- **Country flags in the server list**: each server in the multiplayer list gets its host
  country's flag on the right-hand side. Hover it for the city, region, ISP, network operator,
  timezone and how far away the server is.
- **Server details**: address, MOTD, version, protocol, player count, difficulty, your permission level
- **Location**: `/serverinsight` also reports where the address points, with the same details on hover
- **Server software**: identifies Paper / Purpur / Folia / Spigot / Fabric / Forge / vanilla from the
  brand, with version, and tells you whether the server can run plugins at all. Detects when you're
  connected through BungeeCord, Waterfall or Velocity.
- **Server-side mods**: lists mods the server declares network channels for — something no other
  client-side mod surfaces
- **Plugins**, detected three ways and labelled by confidence:
  - Reads namespaces from the command tree
  - Tab-completes every plugin-listing command the server advertises (`/version`, `/plugins`, `/pl`, …)
  - Recognises ~85 well-known commands (`/lp` → LuckPerms, `/co` → CoreProtect) — these are
    **guesses**, shown greyed out with a `?` and counted separately as `guess:`
  - Includes a **copy button** for the detected list and individual plugins
  - Color codes popular plugins and security/anticheat plugins
- **Player details**: gamemode, ping, coordinates
- **World details**: dimension, biome, time, weather
- **Performance estimate**: TPS, measured from the server's own tick counter

## Note
- **TPS is an estimate**, measured from the tick counter in the time packets the server sends. If a
  server doesn't send them, the mod says **unknown** rather than inventing a number.
- **Plugin detection is not guaranteed** — many servers intentionally hide or restrict this information.
  The `cmd:` / `tab:` / `guess:` breakdown tells you where each result came from; anything marked
  `guess:` was inferred from a command name and is not confirmed.
- **Mod detection is a lower bound.** A server-side mod that never talks to clients is invisible to it.
- **Flags show where the address points**, not necessarily where the server hardware is. Anything
  behind a proxy, CDN or anycast network geolocates to the edge node.
- **Country lookups use a public geolocation API.** Only addresses shown in your server list are
  looked up, results are cached, and private/LAN addresses are never sent. If you are offline or
  the lookup fails, flags simply don't appear. The flag's distance line also asks the same API
  where *you* are, once per session, cached and failing silently.
- This mod is **client-side** and does not need to be installed on the server.

## Installation
1. Install **Fabric Loader** for your Minecraft version
2. Install **Fabric API**
3. Drop the `.jar` into your `mods` folder
4. Launch the game and run `/serverinsight`

## Compatibility
- **Loader**: Fabric
- **Environment**: Client
- **Minecraft**: 26.2
- **Java**: 25+

## Preview
![Server Info](https://cdn.modrinth.com/data/cdJrY41V/images/0fc75292051ee8a174f5f826a19a489b81db2de0.png)
![Country Flag & Info](https://cdn.modrinth.com/data/cached_images/ca100c1cdef0cfa522d059c14bf1984614144db2.png)

## [Support / Suggestions](https://github.com/theflamebeast/server-insight/issues)
If you find a server where output looks wrong, please include:
- Minecraft version
- Fabric Loader version
- Fabric API version
- The exact chat output from `/serverinsight`