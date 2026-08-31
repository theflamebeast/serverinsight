# Changelog

Notable changes per release, written for players rather than for the commit log.
Starts at 1.2.0 — earlier releases predate this file.

## 1.2.1

**Minecraft 26.2.** Requires Fabric Loader 0.19.3+ and Java 25+.

### Added

- **Distance to the server** on the flag hover, in kilometres — how far the address is from
  you, using the same geolocation the flags already use.

### Changed

- The flag hover no longer carries the "where the address points" disclaimer; the place, IP
  and distance lines already say what it was warning about.

### Notes

- The distance line needs your own location, so the mod now asks the same public geolocation
  service where *you* are, once per session. It is cached, happens at most once, and fails
  silently — no location, no distance line.

## 1.2.0

**Minecraft 26.2.** Requires Fabric Loader 0.19.3+ and Java 25+.

### Added

- **Country flags in the server list.** Every server shows where it's hosted, to the
  right of the scrollbar. Hover a flag for the city, region, ISP, network operator and
  timezone.
- **Server software identification.** Instead of a raw brand token, `/serverinsight` now
  names the software — Paper, Purpur, Folia, Spigot, Fabric, Forge, vanilla — with its
  version, and tells you whether the server can run plugins at all. Detects when you're
  connected through BungeeCord, Waterfall or Velocity.
- **Server-side mod detection.** Lists mods the server declares network channels for.
- **Location line** in `/serverinsight`, matching the server list flags.
- Player count, protocol number, difficulty and your permission level.

### Improved

- **Plugin detection now uses three methods** instead of one, and tells you how much to
  trust each result. Alongside namespaced commands it tab-completes every plugin-listing
  command the server offers (`/version`, `/plugins`, `/pl`, and others), and recognises
  ~85 well-known commands such as `/lp` or `/co`.
- The summary breaks results down as `cmd:` / `tab:` / `guess:`. Anything under `guess:`
  was inferred from a command name and is shown greyed out with a `?` — a command name
  is a hint, not proof.
- "None detected" now explains itself. On a vanilla or modded server, no plugins is the
  correct answer rather than a sign the server is hiding something.

### Fixed

- **TPS no longer invents a number.** It used to report a confident 20.00 whenever it had
  no data, so servers that don't send timing packets looked perfect. It now says
  `unknown`. The estimate itself is also measured properly, from the server's own tick
  counter rather than assuming a fixed packet interval.
- **Removed ms/t.** It was calculated as `1000 / TPS` — the TPS reading restated and
  presented as a second measurement. Real ms/t is server-side tick time, which a client
  cannot see.
- **No more freeze on slow DNS.** The address lookup ran on the render thread and could
  hang the game for seconds; it now happens in the background.
- Fixed a timing bug that counted every server tick update twice.

### Notes

- Flags show where the address **points**. Anything behind a proxy, CDN or anycast
  network geolocates to the edge node, not the machine running the game.
- Country lookups use a public geolocation service. Only addresses in your server list
  are looked up, results are cached, and private/LAN addresses are never sent. Offline,
  flags simply don't appear.
- Mod detection is a lower bound — a server-side mod that never talks to clients is
  invisible to it.
