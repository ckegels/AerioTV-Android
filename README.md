<p align="center">
  <img src="docs/arrtv/banner.png" alt="ArrTV" width="480" />
</p>

# ArrTV

ArrTV is an IPTV app for **Google TV / Android TV**, phones and tablets. It plays live TV with a full program guide, movies and series, and recordings from a **Dispatcharr** server, an **Xtream Codes** provider or an **M3U** playlist.

ArrTV is a modified version of [AerioTV for Android](https://github.com/jonzey231/AerioTV-Android) by Logan Jones. It keeps everything AerioTV does and adds a TV player overlay in the style of classic IPTV set-top apps, live updates from Dispatcharr, a compact TV layout, speed fixes for low-end TV boxes, and its own update channel. ArrTV is not affiliated with AerioTV or with the Dispatcharr project.

- [Install](#install)
- [Updates](#updates)
- [First setup](#first-setup)
- [What ArrTV adds](#what-arrtv-adds)
- [Remote control](#remote-control)
- [Features](#features)
- [Supported servers](#supported-servers)
- [Tips for low-end TV boxes](#tips-for-low-end-tv-boxes)
- [Building from source](#building-from-source)
- [License](#license)

## Install

ArrTV is not in the Play Store. Install the APK from this repository's releases.

**Direct download (always the latest version):**
`https://github.com/ckegels/AerioTV-Android/releases/latest/download/ArrTV.apk`

**On Google TV / Android TV**
1. Install the **Downloader** app (by AFTVnews) from the Play Store.
2. In Downloader, enter the direct download link above and install the APK.
3. When asked, allow Downloader to install unknown apps.

Alternatives: send `ArrTV.apk` to the TV with an app like *Send Files to TV* and open it with a file manager, or install it from a computer with `adb install ArrTV.apk`.

**On a phone or tablet:** open the direct download link, then open the downloaded file and allow your browser to install unknown apps.

ArrTV has its own app ID (`com.ckegels.arrtv`), so it installs **next to** AerioTV instead of replacing it. Android 8.0 or newer is required.

## Updates

ArrTV updates itself from the releases of this repository.

- **Settings › App Updates › Check for updates** looks for a new version and shows the result on the button (up to date, a new version, or an error).
- **Check for updates automatically** (off by default) makes ArrTV look for a new version each time it opens and offer it.
- Download and install happen in the app. Android asks once to allow ArrTV to install apps, and shows its own confirmation for each update. Your channels, settings and recordings are kept.
- Each release includes a compile profile for Android 9 and newer, which the updater installs with the update. Android then prepares the app during the install, so it is fast right away instead of only after the TV's overnight maintenance.

Updates are refused while a local recording is running, so a recording is never cut off.

## First setup

1. Open ArrTV. On the welcome screen, ArrTV looks for **Dispatcharr servers on your network** for a few seconds. A server it finds appears as the first option; pick it and enter only your username and password.
2. Otherwise choose **Connect a Server** and pick Dispatcharr, Xtream Codes or M3U.
3. Optional: **Sync via Google Account** carries playlists, favorites, reminders, watch progress and settings between your devices.

Network discovery only searches the TV's own network (the same subnet) on Dispatcharr's standard port 9191. A server in another subnet, behind a VPN or on another port is added with **Connect a Server**.

## What ArrTV adds

Compared with AerioTV:

**Player (TV)**
- **Info bar overlay** (Settings › Player › Overlay Style › Info bar, off by default). Changing channel shows a full-width bar along the bottom: the channel logo, the programme with season and episode, time, progress, minutes left, channel number and name, picture format, description and what's next, with the group and the clock at the top.
- **OK** shows the same bar with a timeline and a row of cards: **TV guide**, **History** and the next channels in the guide; OK on a channel card tunes it.
- **Hold OK** opens the options menu in the middle of the screen: subtitles, audio track, speed, picture scale, record, sleep timer, stream info, switch stream, add to Multiview and audio only.
- Channel up / down wraps around: down on the first channel goes to the last one.

**Dispatcharr**
- **Live updates** (Settings › General › Live updates, off by default, needs a username and password login): ArrTV listens to your Dispatcharr server and applies new, removed or changed channels and guide updates while the app is open, without a restart. Only the channels that changed are rewritten.
- Background data (the scheduled refresh and the guide sweep) now shows up in the open app, and the scheduled refresh also runs while one channel is playing.

**TV layout**
- **Compact modern layout** (Settings › Appearance › Layout, TV only, off by default): a navigation rail on the left instead of the top tab bar, a guide with a group sidebar and a program preview, and compact guide rows with channel number, logo and name on one line. Turning it off restores your previous settings exactly.
- Monochrome theme by default and the ArrTV icon and TV banner.

**Speed and reliability**
- The guide loads several times faster on low-end boxes: cached programmes are read in pages instead of one huge database query (22 s → 6 s on a Chromecast with Google TV HD).
- The guide paints a small time window first and fills in the rest in the background.
- The visible guide is no longer cleared when the device runs low on memory, and it stays on screen while its cache is rebuilt.
- Delete Playlist is instant; the playlist's cached data is removed in the background.
- Guide group pills stay reachable with the remote after picking a group further down the list.

## Remote control

Standard buttons in the player (all of them can be changed in Settings › Remote Control):

| Button | Short press | Hold |
|---|---|---|
| Up / Down | Next / previous channel | Recently watched / Search |
| OK | Show the info bar and cards (or the player controls) | Options menu (info bar style) |
| Left | Channel list | Back to the guide, playback continues in the corner |
| Right | Previous channel | Program info |
| Back | Back to the guide, playback continues in the corner | Stop playback |

In the guide, OK plays the selected channel and holding OK opens the programme menu (reminder, recording, catch-up).

## Features

Everything from AerioTV, including:

- **Live TV and guide**: hardware-accelerated playback (Media3 / ExoPlayer, HLS, DASH, MPEG-TS, HEVC and HDR where the device supports it), a full EPG grid, favorites, hidden groups, reminders, catch-up on channels that support it, and jump to any cached day.
- **Live Rewind**: pause and rewind live TV from a buffer on the device (Settings › Player).
- **Multiview**: up to 9 channels at once, with audio on the selected tile.
- **Recording**: server-side on Dispatcharr (keeps running when the app is closed, with Comskip) and local recording on the device for every server type, with start-early and end-late buffers.
- **Movies and series** from Dispatcharr (with TMDB details) and Xtream Codes, with Continue Watching.
- **Casting** from a phone to Google Cast devices.
- **Picture-in-picture**, sleep timer, audio-only mode, subtitle and audio track selection, stream info, and refresh-rate matching.
- **LAN / WAN switching**: a separate home address for your server that is used automatically on your home network.
- **Google Drive sync** (optional).

## Supported servers

- **Dispatcharr** (recommended), with username and password or an API key. Server-side recording and Comskip, stream switching, TMDB-rich movie and series details, fast bulk guide loading, network discovery and live updates.
- **Xtream Codes**, with the URL, username and password from your provider. Live TV, movies and series, local recording.
- **M3U**, with a playlist URL and an optional XMLTV guide URL. Live TV and local recording.

Over-the-air MPEG-2 channels (for example from an HDHomeRun) play on most TV boxes, but many phones and tablets cannot decode MPEG-2. With Dispatcharr, assign those channels an ffmpeg stream profile that converts them to H.264; see the [AerioTV README](https://github.com/jonzey231/AerioTV-Android#ota--hdhomerun-channels-mpeg-2) for the exact parameters.

## Tips for low-end TV boxes

On boxes with little memory and a slow processor (for example the Chromecast with Google TV HD):

- Update ArrTV through **App Updates**, so it is prepared for your device during the install.
- Close apps you are not using. Android keeps them in memory and the whole device slows down when memory runs out.
- **Live Rewind** writes the channel you watch to storage continuously. Turn it off (Settings › Player) if you do not use pause and rewind.
- A smaller **Guide Days** setting on the playlist keeps less guide data to load at startup.

## Building from source

Requirements: Android Studio (or JDK 21 from JetBrains, as `gradle/gradle-daemon-jvm.properties` asks) and the Android SDK 36.

```
git clone https://github.com/ckegels/AerioTV-Android.git
cd AerioTV-Android
./gradlew :app:assembleGithubDebug
```

- The `github` flavor contains the in-app updater; the `play` flavor does not.
- The updater's release repository, its trusted signing certificate and the default theme are Gradle properties, so a build can point them at its own releases:
  - `aerio.updateRepo=owner/repo` (default: the official AerioTV releases)
  - `aerio.updateKeySha256=<SHA-256 of the release signing certificate>`
  - `aerio.defaultTheme=Monochrome` (any theme name; default `Aerio`)
- The updater only runs in builds signed with the certificate it trusts; debug builds hide it.
- Google Drive sync needs your own Google Cloud OAuth web client ID in `local.properties` as `GOOGLE_DRIVE_WEB_CLIENT_ID`; without it only sync is disabled.

The ArrTV source of every release is on its tag (`v<version>`), and the `personal` branch holds the current combined source.

## License

ArrTV is a modified version of AerioTV for Android.

Copyright (C) 2026 Logan Jones (AerioTV)
Modifications copyright (C) 2026 ckegels (ArrTV)

ArrTV is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

An additional permission under GPL section 7 covers linking with the proprietary Google Play services client libraries. See [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md).

AerioTV releases up to and including v0.4.20 were published under the MIT License. That grant is not revoked: any copy obtained under MIT keeps its MIT rights to that snapshot in perpetuity. Everything from the relicensing commit forward is GPL-3.0-or-later.

### Third-party components

ArrTV bundles an FFmpeg build (LGPL-2.1-or-later) and depends on a number of Apache-2.0 libraries. The FFmpeg build is configured without `--enable-gpl` and enables exactly ten audio decoders: `aac`, `ac3`, `eac3`, `dca`, `truehd`, `mlp`, `mp2`, `mp3`, `flac` and `alac`. Anything outside that set relies on the device's own hardware decoder. See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the full list, the exact FFmpeg build configuration, and where to obtain the corresponding source. The same information is in the app under Settings › About › Open Source Licenses.

The Apache-2.0 components include AndroidX and Jetpack Compose, Media3 / ExoPlayer, Kotlin with kotlinx.coroutines and kotlinx.serialization, Ktor, Dagger and Hilt, OkHttp, Okio (Square), Coil, ZXing Core and Reorderable. They are used under the Apache License, Version 2.0, whose full text is at https://www.apache.org/licenses/LICENSE-2.0 and is also bundled in the app.

FFmpeg is loaded as a dynamically linked JNI shared object (`libffmpegJNI.so`) inside `app/libs/media3-decoder-ffmpeg.aar`, and can be replaced with a modified build, as section 6 of the LGPL requires. Build steps are in [app/libs/README.md](app/libs/README.md).

The Google Play services client libraries (Cast sender, Cast Connect receiver, Play services Auth, Google Identity) are proprietary and are used under the Android Software Development Kit License Agreement and the Google APIs Terms of Service. Linking them with this GPL-licensed program is permitted by an additional permission under GPL section 7; see [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md).

The four-color Google "G" on the Sign in with Google button (`res/drawable/ic_google_g.xml`) is a trademark of Google LLC, used per the Google Sign-In branding guidelines.

### Patents

ArrTV includes independent implementations of audio and video codecs, supplied by FFmpeg, that may be covered by patents in some countries. ArrTV is free software under GPL-3.0-or-later and grants no patent license of any kind; users and redistributors are responsible for compliance with the patent law that applies where they live.

Dolby, DTS and other names that appear in this project are trademarks of their respective owners, used only to identify the audio formats concerned. ArrTV is not affiliated with, endorsed by, or certified by any of them.

### TMDB attribution

This product uses the TMDB API but is not endorsed or certified by TMDB. TMDB data and images are used only after a TMDB API key is configured in the app's settings.

## Support

For problems with ArrTV, open an issue at [github.com/ckegels/AerioTV-Android/issues](https://github.com/ckegels/AerioTV-Android/issues). Please do not report ArrTV problems to the AerioTV project.
