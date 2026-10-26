# KilnLauncher

[简体中文](KILNLAUNCHER.md) | **English**

KilnLauncher is a Minecraft launcher built from the source of
[HMCL **3.16.4**](https://github.com/HMCL-dev/HMCL/releases/tag/v3.16.4) (stable release, not a
snapshot). On top of the free, serverless setup it adds a **room lobby**: a host can publish a
Terracotta multiplayer room so that every KilnLauncher user sees the invite code and can join
with one click.

Current version: **1.0.0**. It is composed from `versionRoot` in `config/project.properties`
and the `BUILD_NUMBER` used at build time, and is written into `assets/hmcl.properties`, the
executable's file version and the window title.

> This project follows the GPLv3 license of upstream HMCL. HMCL is copyright huangyuhui and
> contributors; the KilnLauncher modifications are copyright the KilnLauncher contributors.

## Features

### Room lobby

- Lives on the "Multiplayer" → "Room Lobby" tab.
- Only rooms that a **host explicitly published** are listed. Private rooms are never uploaded.
- Shows the room name, host name, player count and invite code.
- Clicking a row confirms and joins the room.
- The copy button on the right copies the invite code for sharing.
- Refreshes every 15 seconds while visible; a manual refresh button is available.

### Bilibili sign-in

The room lobby and the publish switch require a bound Bilibili account:

- Clicking "Room Lobby" while signed out shows the "Sign in with Bilibili" dialog.
- Scan its QR code with the Bilibili mobile app and confirm on the phone.
- Credentials are stored only in `.kilnlauncher/private/bilibili-account.json`.
- The lobby header shows the bound account's avatar and name, with a sign-out link.
  Signing out gates the lobby and the publish switch again.
> The check runs in the client, so it binds an identity but cannot stop a modified client.

### Publish switch

Create a room under "Multiplayer" → "Status"; once it is started, a
**"Publish to room lobby"** switch appears:

- On: publishes the invite code immediately and sends a Ping every 60 seconds.
- Off: removes the room immediately.
- Leaving the room, switching to guest mode or a Terracotta failure removes it as well.
- If the launcher crashes, the room disappears automatically after **4 minutes** (Ping
  timeout).

> The invite code of a public room is visible to everyone, and anyone holding it can join.
> Do not reveal private information in a public room.

Privacy: publishing sends the room name, host name, player count, instance name and invite
code to a public topic. After turning it off (or after the Ping expires) the lobby stops
showing the room within 4 minutes, but messages already published to ntfy.sh cannot be
recalled and may remain in the server cache for up to 12 hours.

## How it stays free

The lobby uses a **public topic** on [ntfy.sh](https://ntfy.sh) as a message bus — no server,
account, database or paid service:

1. When a host enables publishing, the launcher sends a JSON Ping to
   `https://ntfy.sh/kilnlauncher-rooms-v1` containing the room name, host, player count,
   invite code and `visibility: "public"`.
2. The Ping is repeated every 60 seconds; ntfy keeps recent messages for 12 hours.
3. Each launcher polls the last 20 minutes of the same topic, deduplicates by room id, keeps
   the newest message per invite code and drops rooms without a Ping for 4 minutes.
4. Closing a room sends an `action: "close"` message for immediate removal.

Because the topic is public, no identity is required — and anyone can write to it, so clients
validate format, length, visibility and timestamps. A community can move the lobby to a
self-hosted ntfy instance without rebuilding:

```text
-Dkiln.rooms.server=https://your-ntfy.example.com
-Dkiln.rooms.topic=your-private-topic
```

Every launcher using the same `server` + `topic` shares the same lobby.

### Why only KilnLauncher rooms?

Upstream HMCL has no "publish room" feature and never publishes invite codes anywhere, so
private rooms created with HMCL cannot be discovered. The lobby lists rooms from users who run
KilnLauncher **and** chose to publish.

## Running (Windows executable)

A single-file executable is produced at `dist/KilnLauncher.exe`. Double-clicking it works; the
file name, icon and version information all say KilnLauncher. It consists of the HMCL native
bootstrap plus the KilnLauncher JAR, and requires **Java 17 or newer** (21 recommended). The
first run creates `.kilnlauncher` next to the executable. If an old `.hmcl` directory exists,
its configuration is **copied once** into `.kilnlauncher` (the old directory is never moved or
modified), so accounts and instances keep working — and HMCL itself still works.

If SmartScreen reports an unknown publisher, choose "More info" and then "Run anyway".

## Branding and data directory

- The launcher's own configuration and dependencies live in **`.kilnlauncher`**.
- An existing `.hmcl` directory is copied (not moved) on first launch, so HMCL stays usable.
- Window, title bar, taskbar and executable icons are KilnLauncher's K icon.
- HMCL's preview announcements, official group, feedback and help links were removed to avoid
  confusion with HMCL.
- The `.hmcl` directory *inside a game instance* is instance metadata (config, modpack info).
  It is kept for compatibility with existing instances and is not the launcher's data directory.

## Terracotta compatibility

KilnLauncher bundles Terracotta **0.4.2**, matching HMCL 3.15.2+, HMCL 3.16.4 and FCL.

- Host and guest should use the same Terracotta generation; mixing them can make joining fail.
- After joining, the host must keep the world open to LAN, guests must use the address shown by
  Terracotta, and both sides need the same mods.
- The lobby does not carry a Terracotta version marker yet, so mixed versions may see a room but
  fail to join.

## Relays (EasyTier)

A Terracotta room code only works if both peers can meet on an EasyTier relay. If every relay is
unreachable, the host disconnects immediately with `PingServerRst` and the guest sees
`Cannot find scaffolding server`.

HMCL fetches its relay list from `https://terracotta.glavo.site/nodes`, which is dynamic:
entries can be removed or return empty bodies, leaving only unusable relays. KilnLauncher adds
three layers of fallback (`terracotta/TerracottaNodeList.java`):

1. A built-in known-good relay, always first;
2. the remote list, deduplicated and order preserved;
3. nodes from `-Dkiln.terracotta.nodes=...` (comma or space separated).

Extra relays can be supplied without rebuilding (they must be set in the environment of the
process that starts KilnLauncher):

```bat
set "JAVA_TOOL_OPTIONS=-Dkiln.terracotta.nodes=tcp://your-relay:11010"
start "" "KilnLauncher.exe"
```

The log prints both lists so the effective nodes can be verified:

```text
Terracotta node list: [...]
Effective Terracotta node list: [...]
```

## Tags, filtering and latency

Above the "Publish to room lobby" switch a **custom room name** can be entered; leaving it empty
uses the game instance name. The name is applied on Enter or when the field loses focus, and the
room is republished immediately if it is already public.

After publishing, a row of tags appears below the switch (multi-select): vanilla, survival,
creative, adventure, puzzle, horror, parkour, PvP, PvE, hunter game, modded. Tags can be changed
at any time and republish the room immediately.

The lobby has the same tags for filtering (multi-select, a room matches any selected tag; "All"
clears the filter). Rows show the host's tags and latency.

The lobby also has a search box for **room name** and **host name**. Host names only match when
the host signed in with a Microsoft account — offline accounts can use any name, so including
them would return unrelated rooms. The matching lives in `PublicRoom#matchesQuery` and does not
depend on the UI.

Rows show Ping age, e.g. "Online · Ping 12s ago". Hosts send a Ping every 60 seconds,
so a room is only marked "May be offline" after three missed Pings (3 minutes).

Implementation notes (`terracotta/lobby/`):

- `PublicRoomTag` defines the fixed tag set. Only ASCII ids travel over the wire and the display
  name is translated locally, so older launchers ignore unknown tags instead of failing.
- `PublicRoom` gained optional `tags` and `latency` fields. Receivers drop unknown tags,
  deduplicate, keep at most 8, and clamp latency to 0–10000 ms.
- Latency is the **host-to-lobby-server (ntfy) round-trip time**, measured by the host and
  written into the Ping — it describes the host's connection, not the guest's ping to the
  host. The first request includes DNS and TLS, which inflates it to ~900 ms, so publishing
  sends once, measures again and uses the second value; Pings refresh it every 60 seconds.
  Hovering the latency badge shows a tooltip.

## Room password

A host can enable the "Room password" switch and type a password (applied on Enter):

- The lobby shows a lock icon and "Invite code: encrypted".
- Copying the code and joining both require the password first.
- A wrong password is reported inside the dialog, without reopening it.

Implementation (`terracotta/lobby/PublicRoomPassword.java`):

- The lobby is a public topic, so hiding the code in the UI would be pointless — anyone can read
  the raw message. With a password the code is **really encrypted** before publishing:
  `PBKDF2WithHmacSHA256` (100k iterations) derives a 256-bit key, then
  `AES/GCM/NoPadding` with a random 16-byte salt and 12-byte IV. The payload is
  `v1:` + base64(salt | IV | ciphertext+tag).
- The public message contains only `locked: true` and `codeCipher`; a wrong password fails the
  GCM authentication and reveals nothing.
- This stops casual strangers, not a targeted brute-force attack on the public topic: strength
  depends on the password the host chooses.
- Turning the switch off clears the password and republishes the room as open.

## Mod dependency installation

Downloading a mod version under "Download" → "Mods" makes KilnLauncher:

1. resolve the **required** dependencies in the background, following the chain up to 4 levels;
2. pick versions that match the target instance (game version and mod loader);
3. scan the instance's `mods` directory and skip anything already installed;
4. show a confirmation listing "will be installed too" and "already installed, skipped", then
   download everything in one go.

Implementation notes:

- Resolution lives in `ui/download/ModDependencyResolver.java`, uses the dependency metadata of
  Modrinth / CurseForge, and uses `ModManager` (mod id and display name) to detect installed mods.
- Only `required` dependencies are installed; optional ones are left alone.
- Dependencies without a compatible version are listed separately for manual installation.
- When dependencies are involved, remote file names are used instead of prompting per file.

## Download sources and merged search

The "Download source" selector on the mods / resource packs / shaders pages offers:

| Option | Meaning |
| --- | --- |
| Modrinth | Only Modrinth, works out of the box |
| CurseForge | Only CurseForge, needs an API key |
| Merged | Both sites at once, deduplicated by name (only shown once a key is configured) |

Why the selector used to be missing: official HMCL builds embed a CurseForge API key in the JAR.
A self-built launcher has no key, so CurseForge is considered unavailable and the selector was
hidden entirely. KilnLauncher instead:

- always shows the selector; choosing CurseForge without a key explains where to configure it
  and switches back to Modrinth;
- adds a **CurseForge API Key** row under "Settings → Download" that takes effect immediately;
- shows "Merged" in the selector once a key is configured.

Merged mode deduplicates by
"slug + title"; because category ids differ between the sites, a selected category searches
Modrinth only.

## Building

JDK 17 or newer is required.

```powershell
# Run
.\gradlew.bat :HMCL:run

# Build the runnable JAR (output in HMCL/build/libs)
.\gradlew.bat :HMCL:shadowJar

# Build single-file executables (native bootstrap + JAR, HMCL-*.exe)
.\gradlew.bat :HMCL:makeExecutables
```

`dist/KilnLauncher.exe` uses the same bootstrap as `HMCL-*.exe`, with KilnLauncher's icon,
product name and version written via `rcedit`.

If downloading the Gradle distribution from GitHub fails, fetch the same version from a mirror
(`gradle-9.8.0-bin.zip`) and run the same tasks with `gradle.bat`.

## Differences from upstream

| File | Change |
| --- | --- |
| `Metadata.java` | Launcher name, `.kilnlauncher` data directory, upstream auto-update disabled |
| `EntryPoint.java` | Copies legacy `.hmcl` configuration into `.kilnlauncher` on first launch |
| `assets/img/icon*.png` | Window/taskbar/title icons replaced |
| `MainPage.java` | HMCL preview announcement removed |
| `RootPage.java`, `LauncherSettingsPage.java` | HMCL group, feedback and help entries removed |
| `AboutPage.java` | KilnLauncher version info, upstream attribution kept |
| `TerracottaPage.java` | "Room Lobby" tab added |
| `TerracottaControllerPage.java` | Publish switch, room name, tags and password added |
| `terracotta/lobby/*` | Room model, ntfy directory client, Ping publisher, lobby page |
| `terracotta/lobby/PublicRoomTag.java` | Tag set, published with rooms and used for filtering |
| `terracotta/lobby/PublicRoomPassword.java` | Room password: PBKDF2 + AES-GCM encryption of the code |
| `ui/construct/InputDialogPane.java` | Masked input variant for the room password |
| `terracotta/TerracottaNodeList.java` | Built-in relay and `kiln.terracotta.nodes` fallbacks |
| `I18N*.properties` | New strings, window title |
| `assets/css/root.css` | Tag and latency badge styles |

Everything else follows upstream HMCL; its documentation, download sources and relays still
work.

## FAQ

### Stuck on "Joining room"

Terracotta is **peer-to-peer**, not a relay server; whether it connects depends on both NAT
types. The lobby only shares the invite code and takes no part in the connection, so a failure
is not the lobby's fault. Try:

1. Change networks on both sides (mobile hotspot, wired, different ISPs).
2. Disable proxies/VPNs, or use a global proxy (proxies can break UDP hole punching).
3. Make sure the host stays on the started-room screen and the world is open to LAN.
4. Do not host and join from **the same computer**; EasyTier fails on a machine_id conflict.
5. "Current network status is very poor" means a direct connection is unlikely — changing
   networks usually helps more than retrying.

### Slow downloads in mainland China

- The "Auto" download source measures the official source and BMCLAPI at startup and picks the
  faster one; before the measurement finishes, Chinese locales prefer the mirror.
- "Settings → Download" lets you choose: mirror (BMCLAPI), official (Mojang) or auto.
- If BMCLAPI's CDN keeps timing out, switch to the official source with a system proxy, and vice
  versa.
- A custom BMCLAPI mirror can be set with `-Dhmcl.bmclapi.override=https://your-mirror`.

## Disclaimer

The lobby depends on the third-party public service ntfy.sh; its availability, rate limits and
retention policy may change. The project is not responsible for strangers joining a room because
its invite code was published — evaluate the risk before publishing.
