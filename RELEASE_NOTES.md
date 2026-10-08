# Release Notes

Changes marked **Data** are delivered through the remote channel config or EPG feed and apply to all installed versions without an app update.

---

## 1.9.0 (2026-10-08)

### New
- Main screen: favorite channels are shown as a row of tiles at the top, and all channels can be shown as a list or as tiles (the choice is remembered). Favorites are marked with a small star on the logo.
- Stream check: channel streams are checked automatically every day. A channel with a playback problem is dimmed and marked "점검 중" (under check) in the app and in Android Auto. It can still be played, and the mark is removed once the stream works again.

### Improved
- Top banner: new KRadio logo text (same look on every phone), with sound bars that move while playing.

### Technical
- Stream health check runs on the server (ffmpeg decodes a few seconds of each stream and checks for silence); results are published to `status.json` on the `status` branch only when the set of problem channels changes. The app reads it through the GitHub contents API (raw fallback) on start, on return to the app and every 5 minutes while open.
- Logo font: Poppins ExtraBold (SIL Open Font License 1.1) bundled for the banner only.
- `scripts/mirror.py` builds the main-root copy of the channel settings for older versions; an optional `legacyNameSuffix` is appended to the latest version name in that copy only.

---

## 1.8.0 (2026-10-06)

### Improved
- Channel logos show again in apps that display what is playing, such as floating music players and Android-based car head units. The logo of the playing channel is now sent as image data instead of a link those apps could not open.
- Android Auto: the now playing background changes only once when switching channels.
- Logos of channels added to the channel settings later (not bundled in the app) are now shown in Android Auto and on Galaxy Watch.
- Settings: section headings with icons; scheduled start removed from Settings (open it from the top banner).
- Player: the playing status uses a small green dot.

### Changed
- The channel settings file moved to a separate location. Older app versions keep working with the previous location.

### Technical
- Channel settings (`channels.json`) and the data format guide moved to the `config` branch; the copy at the main branch root is kept in sync for older versions.
- `scripts/config.sh` for editing channel settings; `release.sh` updates the latest version in the `config` branch and copies it to main.
- Now playing metadata sent outside the app carries artwork data only; playlist items keep content URIs.
- Media3 deprecations removed (connection result builder, playback resumption callback).

---

## 1.7.2 (2026-10-04)

### Fixed
- Logos of custom channels (logo set to a web address) not shown in Android Auto and on Galaxy Watch. These logos are now downloaded once, kept on the phone and provided the same way as built-in logos. A new logo address is downloaded again automatically.

---

## 1.7.1 (2026-10-04)

### Fixed
- Widget volume buttons responding several seconds late right after starting playback from the widget. The play button held its request for 8 seconds, which queued every other widget button behind it.
- Widget volume buttons, the app screen and the muted-playback check woke up Android Auto on the phone even when not in the car, causing a delay on the first press after a while. The car connection is now checked only when Android Auto is already active.

### Improved
- Widget previous, next and stop buttons release their request sooner, so the next button press is handled right away.

---

## 1.7.0 (2026-10-03)

### New
- **Text size** (Settings → Display → Text size): follow the phone setting (default), or a fixed size for the app regardless of the phone font size: small 85%, normal 100%, large 115%, extra large 130%. Applies to all app screens and dialogs; the widget follows the phone setting.

### Improved
- Player: channel name, program and playback status on three separate lines, so the channel name uses the full width.
- Top banner: sleep timer, scheduled start and settings now look like buttons (rounded background with a short label under the icon), placed closer to the right edge and always within the right half.

### Fixed
- Settings button announced as "channel management" by screen readers.

---

## 1.6.1 (2026-10-03)

### Improved
- Home screen widget fits narrower screens such as bar-type phones.
  - Play buttons and volume share one row from 300dp wide (was 330dp); the date and time are shown at that width with a smaller clock.
  - When the widget is short, the volume row and then the favorites row are hidden instead of being cut off.
  - When there is spare height, the logo, buttons and favorites get larger instead of leaving empty space.
  - Buttons, favorites and the volume gauge use a lighter background so they stand out from the widget.

### Fixed
- Favorites row cut off at the bottom of the widget on some phones.

---

## 1.6.0 (2026-10-03)

### New
- **Android Auto support**: the app now appears as a media app in Android Auto.
  - Three tabs with icons: Favorites (large logo grid), Channels (list) and Channels (tiles).
  - Each channel shows its logo and the program currently on air.
  - Now playing screen shows the channel logo and the current program with its time range (e.g. "Morning Show (07:00–09:00)"). The same title and time range appear in the Android Auto queue and on Bluetooth car displays; the phone notification and lock screen keep the program name only.
  - Previous / next, play / stop and channel selection from Android Auto, the steering wheel buttons, the phone app and the widget all control the same playback.
- **Volume while connected to Android Auto**: the car controls the output volume, so the device media volume no longer applies.
  - Player bar: switches to the app volume slider (0–300%) with a "car" label while connected, even in system mode; the mute button uses the app volume. Returns to the previous mode automatically after disconnecting (the system mode setting itself is unchanged).
  - Widget: the volume buttons adjust the app volume in 20% steps while connected, with a 15-step gauge (5 steps = 100%) and a percentage label.
- App volume changed from the widget is reflected in the player bar immediately.
- **Change history** (Settings → App info): release notes for every version in Korean, with the installed version marked.

### Improved
- Muted-playback auto-stop now triggers after 1 minute instead of 3 minutes.
- Muted-playback auto-stop ignores the device media volume while connected to Android Auto (the phone speaker volume may be 0 while sound plays through the car).
- Channel logos are provided to external displays (Android Auto, notifications) through a read-only logo provider, and as image data for the channel currently playing (Galaxy Watch).

### Fixed
- Android Auto now playing background flickering every few seconds while a live stream was playing.
- Android Auto queue list jumping back to the top while scrolling during playback.
- Muted-playback auto-stop stopping playback through Android Auto when the phone media volume was 0.

### Notes
- Android Auto shows apps installed outside the Play Store only when **Unknown sources** is enabled in Android Auto developer settings (Android Auto settings → tap the version 10 times → ⋮ → Developer settings).
- To continue playing automatically when the phone connects to Android Auto, turn on Android Auto's **start media automatically** setting.
- Grid and tile sizes in Android Auto are decided by the car display, not by the app.

### Technical
- Playback service changed from MediaSessionService to MediaLibraryService (browse tree for Android Auto; legacy MediaBrowserService compatible). Registered as an Android Auto media app (automotive_app_desc).
- LogoProvider: bundled logos served as content://<package>.logos/<file>.png (read-only, logo files only, cached per app version).
- CarLink: Android Auto connection state from the car connection provider plus its update broadcast (package visibility query declared).
- Live HLS timeline updates that do not change the channel list (playlist refresh only) are no longer forwarded to the session, so the Auto queue is not re-sent every few seconds.
- Periodic session position updates disabled (live radio has no meaningful position).
- Car displays use displayTitle/subtitle; notification uses title/artist.
- Connected controller version is logged for diagnostics.

---

## 1.5.0 (2026-10-01)

### New
- **Home screen widget**: channel, program and logo; previous / play-stop / next.
  - Larger sizes add device media volume buttons with a step gauge (one bar per volume step), up to 3 favorite channels (play directly without opening the app), and a date/clock.
  - Play from the widget resumes the channel shown on the widget.
  - Widget buttons may respond slightly slower than the app.
- **Favorites** (star in channel management, up to 3): shown as app icon shortcuts (long press) and on the widget.
- Auto-off and scheduled play buttons in the top banner.
- Up/down buttons for all time inputs (hours by 1, minutes by 5; hold to repeat).
- Playback stops automatically after 3 minutes of muted playback (app volume 0% or device media volume 0), with a notification.

### Improved
- Player bar: channel name and playback state on one line, larger controls aligned with the logo.
- Auto-off now starts fading at the set time (then stops 10 seconds later), to account for the delay of internet streams.
- Scheduled play: next run shown next to the time input.

### Fixed
- Channel logo missing on Android Auto and other external displays.

---

## 1.4.0 (2026-09-30)

### New
- **Scheduled play ("켜짐 예약")** (Settings → Scheduled play): start a channel automatically at a set time.
  - Multiple schedules, each with its own switch; repeat on selected weekdays or run once.
  - Pick a channel, then optionally pick a program from the guide to fill in the start time and auto-off automatically.
  - Sets the device media volume for the schedule and fades in over 10 seconds.
  - Optional auto-off after 30/60/90 minutes or when the program ends (uses the auto-off timer).
  - Works with the app closed and the screen off; survives reboots, app updates and time zone changes.
  - Shows when the schedule will run next (e.g. "tomorrow 8:14 AM") to prevent AM/PM mistakes.
  - Delete directly from the list, with undo.
  - Notifications when the stream cannot be reached, when the device media volume is zero, or when the scheduled channel is hidden or deleted.

### Improved
- After a pause longer than 3 seconds (phone call, navigation voice, Bluetooth pause), playback resumes at the live point instead of playing stale buffered audio and then jumping ahead.
- The channel config is checked again when the app comes to the foreground after 15 minutes, so update notices and stream changes appear without restarting the app.
- The notification permission is requested only when adding a new schedule.

### Technical
- Target SDK set to 36 so scheduled playback can start from the background under Android 17 background audio restrictions (compiled against SDK 37).

---

## 1.3.0 (2026-09-29)

### New
- **Volume slider in the player bar** (always visible), replacing the App volume setting.
  - App mode: controls this app only, 0–300% (0% mutes; above 100% uses loudness enhancement, 5% steps).
  - System mode (checkbox): controls the device media volume directly and follows the hardware volume buttons. Switching to system mode resets the app volume to 100%.
  - Speaker icon toggles mute and restores the previous level.
  - A one-time tip explains the limits of amplification the first time the volume goes above 100%.

### Improved
- Larger round slider thumb and taller touch area for easier dragging.
- The volume row sits above the playback controls to stay clear of the bottom system gesture area, and side back gestures are excluded on that row.

---

## 1.2.0 (2026-09-28)

### New
- **App volume** (Settings → App volume): 50–200%, applied to this app only.
  - Uses a perceptual scale (200% ≈ +10 dB, 50% ≈ −10 dB). Above 100% uses loudness enhancement.
  - Most effective on channels with quieter source audio; channels already mastered loud get only slightly louder.
  - The auto-off fade-out follows the app volume.

### Improved
- Loading the channel config and program guide retries once after a transient failure, with a longer timeout.
- Background refreshes by the playback service no longer raise connection warnings on screen (a successful refresh still clears them).
- Failure reasons are logged for troubleshooting.
- Only programs for configured channels within a yesterday–day-after-tomorrow window are kept in memory, so large custom program guides stay lightweight.
- Program guide loading errors can no longer terminate the app.
- Auto-off: times that have already passed or are less than a minute away are rejected, and programs ending within a minute are not listed.

### Fixed
- A channel config connection warning could appear right after installing or updating the app.

---

## 1.1.1 (2026-09-27)

### Fixed
- A program guide connection warning could appear right after launching the app (typically after an update), even though the guide loaded correctly. The screen and the playback service no longer download the guide at the same time; channel config loading is serialized the same way.
- The remaining time of the auto-off timer stayed on the player bar for up to a minute after stopping playback. It now disappears immediately, including when stopped from the notification or a watch.

---

## 1.1.0 (2026-09-27)

### New
- **Auto-off timer** (moon button in the player bar): stop playback after 15/30/45/60/90 minutes, at a chosen time, or when one of the next programs on the current channel ends (up to 4, multi-part programs listed separately).
  - Fades out over 10 seconds before stopping.
  - Keeps running with the screen off and across channel changes.
  - Cancelled when playback is stopped manually.
- Remaining time is shown in the player bar.

---

## 1.0.4 (2026-09-26)

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
