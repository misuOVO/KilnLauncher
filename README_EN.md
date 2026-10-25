# KilnLauncher

[简体中文](README.md) | **English**

KilnLauncher is a Minecraft launcher built from the source of [HMCL **3.16.4**](https://github.com/HMCL-dev/HMCL/releases/tag/v3.16.4)
(the stable release, not a snapshot). It focuses on one thing HMCL does not do: a
**free, serverless public room lobby** for Terracotta multiplayer, so that anyone using
KilnLauncher can see a host's invite code and join with one click.

Current version: **1.0.0**

## Features

### Room lobby

- A host turns on "Publish to room lobby" on the started-room screen; the invite code then
  appears in every KilnLauncher user's lobby. Rooms stay private unless the host opts in.
- **Tags** for every room: vanilla, survival, creative, adventure, puzzle, horror, parkour,
  PvP, PvE, hunter game, modded.
- Lobby filtering by tag and free-text search by room name or host name.
  Host names are only matched for Microsoft accounts, because offline accounts can use any name.
- Each row shows the host's measured latency and `Ping Xs ago`; a room is only marked as
  possibly offline after three missed heartbeats (3 minutes).
- Optional custom room name; falls back to the game instance name.

### Room password

When enabled, the invite code is encrypted with `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding`
before it is published. The public topic never contains the plain code; guests must type the
password before they can reveal the code, copy it, or join.

### Mod dependencies

Downloading a mod resolves its **required** dependencies, picks versions that match the
target instance (game version and mod loader), and skips anything already installed in the
instance's `mods` folder.

### Download sources

The mod / resource pack / shader pages can search **Modrinth**, **CurseForge**, or **both
merged**. Merged mode queries both sites and removes duplicates by name.

> CurseForge requires your own API key (free at `console.curseforge.com`). Enter it under
> "Settings → Download → CurseForge API Key", or bake it into a build with the
> `CURSEFORGE_API_KEY` environment variable.

### Other changes

- Data directory renamed to `.kilnlauncher`; configuration is copied from an existing `.hmcl`
  on first launch
- Upstream HMCL auto-update is disabled
- Window, taskbar and executable icons/names replaced with KilnLauncher's

## Requirements

- JDK 17 or newer (21 recommended)
- Windows, macOS or Linux

## Building

```bash
# Build the runnable JAR (output in HMCL/build/libs)
./gradlew :HMCL:shadowJar

# Build the single-file executable
./gradlew :HMCL:makeExecutables
```

Optional environment variables:

| Variable | Purpose |
| --- | --- |
| `CURSEFORGE_API_KEY` | Embed a CurseForge API key so users do not have to enter one |
| `BUILD_NUMBER` / `VERSION_TYPE` | Control the version number (see `versionRoot` in `config/project.properties`) |

## Usage

The first launch creates a `.kilnlauncher` folder. Multiplayer lives under
"Multiplayer → Status"; the lobby is under "Multiplayer → Room Lobby".

The lobby is built on a public [ntfy.sh](https://ntfy.sh) topic — no account and no server
required. It can be pointed at a self-hosted instance:

```bash
-Dkiln.rooms.server=https://ntfy.sh
-Dkiln.rooms.topic=kilnlauncher-rooms-v1
```

If the upstream EasyTier relay list is degraded, the launcher automatically falls back to a
known working relay. Additional relays can be supplied with
`-Dkiln.terracotta.nodes=...`.

## Known limitations

- The room lobby is a public topic: anyone who reads the messages can see what a room
  publishes. The room password keeps strangers out, but it cannot stop a targeted brute-force
  attack — use a reasonably long password.
- Terracotta 0.4.2 dropped support for the PCL CE room format, so PCL CE rooms cannot be joined.
- Terracotta is peer-to-peer. Whether a connection succeeds depends on both networks; VPNs and
  TUN adapters often break it.

## Relationship to upstream

This is an unofficial fork of HMCL and is not affiliated with the HMCL team, Mojang, or
Microsoft.

- Upstream: <https://github.com/HMCL-dev/HMCL>
- Detailed documentation: [README_EN.md](README_EN.md) · [KILNLAUNCHER_EN.md](KILNLAUNCHER_EN.md) (English) · [KILNLAUNCHER.md](KILNLAUNCHER.md) (Chinese)
- Terracotta is a third-party open-source project; it is downloaded at runtime and is not
  bundled with this repository.

## License

Licensed under **GPLv3**, the same license as upstream HMCL. See [LICENSE](LICENSE).

HMCL is copyright huangyuhui and contributors; the KilnLauncher modifications are copyright
the KilnLauncher contributors.
