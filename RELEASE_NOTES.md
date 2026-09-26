# Release Notes

Changes marked **Data** are delivered through the remote channel config or EPG feed and apply to all installed versions without an app update.

---

## 1.0.4 (Unreleased)

### Improved
- Startup playback now also applies when returning to the app after playback has been stopped for 10 minutes or more (e.g. after the playback notification has disappeared), not only after the app was swiped away.
- Short pauses under 10 minutes and screen recreation (folding/unfolding) do not trigger startup playback.

---

## 1.0.3 (2026-09-26)

### New
- **Startup playback setting** (Settings → Startup playback): Off / Last channel / Fixed channel.
  - Applied when the app is launched fresh. Playback is never interrupted when the app is reopened while already playing, or when the screen is recreated (e.g. folding/unfolding).
  - The Bluetooth play button (playback resumption) follows the same setting. When set to Off, it falls back to the last channel.
  - The play button in the player bar follows the same setting when nothing is queued.
  - If the fixed channel is hidden or deleted, the last channel is used instead.
  - The channel picker reflects channel management (hidden channels excluded, custom channels included, user order).
  - App name is shown in Korean on devices set to Korean, so voice assistants can find the app by its spoken name.

### Fixed
- Program info and logo were not shown in the notification, lock screen and watch until the next minute after starting playback. They now appear immediately.
- Reordering or hiding channels during playback now updates program info in the watch queue immediately.

---

## 1.0.2 (2026-09-25)

### New
- **Update notice**: when a newer version is available, a card appears below the top banner ("Later" hides it for that version).
- **App info** section in Settings: current version and a shortcut to the download folder.
- Channel logos are **bundled in the app**: they load instantly and work offline. Logo files are no longer referenced by URL for built-in channels.
- The watch queue shows the **current program for every channel**, not only the playing one.
- The logo of the playing channel is sent as image data, so it appears on devices that cannot read app-internal files (e.g. Galaxy Watch).

### Improved
- The playback service now re-fetches the channel config when it receives an unknown channel, every 30 minutes, and on reconnect. Stream address changes fixed in the remote config are picked up without restarting the app.
- Top banner and player bar changed to straight edges.

### Data
- Added one new channel.
- EPG added for two channels that previously had no program data (additional EPG source enabled).

---

## 1.0.1 (2026-09-25)

### New
- The **current program** is shown in the notification, lock screen, Galaxy Watch and Bluetooth car displays (title: channel name, artist: program). Updated every minute and on channel change without interrupting playback.

---

## 1.0.0 (2026-09-25)

Initial release.

### Playback
- Background playback with Media3; keeps playing with the screen off or after the app is swiped away.
- 16 built-in radio channels.
- Stream addresses are resolved on the device right before playback (including channels whose stream address is issued per session, with provider-specific request headers). No relay server required.
- Previous/next channel switching from the notification, lock screen, Bluetooth devices and Galaxy Watch (including selecting a channel from the watch queue).
- Bluetooth play button resumes the last channel even when the app is not running.
- Automatic reconnection with backoff (1s → 30s), immediate reconnection on network recovery, and fresh stream address resolution on each retry (handles expired tokens).

### Screens
- Main screen: channel list with logos, current program and time slot; player bar; top banner with date/time.
- Short EPG entries under 5 minutes (weather, campaigns) are skipped in favor of the surrounding main program.
- Channel management: drag to reorder, show/hide, add custom channels (HLS, MP3, AAC), edit name/stream/logo of any channel, per-channel and full reset.
- Settings: configurable channel config and EPG sources with "restore default"; warning card when a source cannot be reached.
- In-app data format guide with copyable examples (channels.json, epg2xml).

### Data
- Channel config (`channels.json`) and EPG (XMLTV) are fetched remotely and cached on the device; the last copy is used when offline.
