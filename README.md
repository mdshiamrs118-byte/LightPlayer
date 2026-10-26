<div align="center">

# 🎬 Light Player

**A fast, lightweight Android video player with extra audio tracks and subtitle-to-speech.**

![Platform](https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Player](https://img.shields.io/badge/player-Media3%20ExoPlayer-4C8DFF)
![Build](https://img.shields.io/badge/build-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)

[Features](#-features) · [Getting started](#-getting-started) · [How to use](#-how-to-use) · [Building](#-building-from-source) · [Troubleshooting](#-troubleshooting)

</div>

---

## ✨ What is Light Player?

Light Player is a small, no-nonsense local media player for Android. It scans your device (including the SD card) for videos, groups them by folder, and plays them in a clean full-screen player with gesture controls.

On top of normal playback it does two things most players don't:

- **Extra audio tracks.** Add your own audio file (a dub, a commentary, a different language) and switch to it at any time. It stays in sync when you seek or pause.
- **Subtitle-to-speech.** Load a subtitle file and Light Player turns it into a spoken audio track using your phone's built-in text-to-speech engine, then plays it in sync with the video.

It has no ads, no accounts and no internet requirement. Everything stays on your device.

## 🚀 Features

### Look and feel
- **Themes:** Mode **System / Light / Dark / Black**, six color presets (**Sunset, Forest, Ocean, Violet, Rose, Mono**) and a **custom hue slider**. Changes apply instantly, with a smooth crossfade, across the whole app
- **Floating pill navigation:** the selected tab expands into a capsule with its label, and the bar animates its width as you switch tabs. Prefer the traditional look? Switch to the **Classic** full-width bottom bar in Settings → Appearance → Navigation style
- Rounded cards, soft gradient backgrounds in light mode, animated tab transitions, list entrance animations, animated folder expand/collapse, press effects and fading player controls
- The player's seek bars and accents follow your chosen color

### Playback
- Powered by **Media3 ExoPlayer**: wide format and codec support, with automatic decoder fallback
- **Resume where you left off:** positions are remembered per video, and thumbnails show a progress bar for videos you've started
- **Playback speed** from 0.5x to 2x; the added audio track and the spoken (TTS) track stay in sync at any speed
- **Swipe gestures:** swipe up or down on the **left** side for brightness and on the **right** side for volume, with an on-screen indicator
- **Pinch to zoom:** pinch out to fill the screen, pinch in to fit. **Fit / Fill / Stretch / 16:9 / 4:3** are in the player menu
- **Previous / next video** and **auto-play next** within the same folder (sorted naturally, so Episode 2 comes before Episode 10)
- **Background audio:** keep listening when you leave the app or turn the screen off, with a notification to pause, resume or close
- **Player menu:** one tidy **gear button** replaces the pile of small buttons. It spins and slides up a slim rail of icons (**Playback speed, Aspect ratio, Audio tracks, Subtitles and speech, Spoken subtitles**). Tap the **>** button at the top of the rail to expand it and see every button's name, description and current value
- **Floating window (our own picture-in-picture):** play the video in a small window **over other apps**. Drag it anywhere, **pinch or drag the corner handle to resize** it (it keeps the video's shape, and remembers its size and place), **double-tap to play/pause**, and **tap once to show or hide** the **−10 / +10**, **previous / next video**, expand and close buttons. The window has softly rounded corners, a thin progress line, and carries over your position, speed, audio track, added audio and spoken track. Start it from the player menu, or turn on **Auto floating window** in Settings to open it when you leave the app mid-video
- **Pin your favorites:** long-press any menu item to pin it next to the gear as a quick button (speed and aspect show their current value). Pinned buttons scroll sideways, and long-pressing a pinned button unpins it
- **Screen rotation button** in the player menu: each tap cycles **Portrait → Landscape → Reverse portrait → Reverse landscape → Auto** (Auto is the default and follows your phone's sensor and rotation lock). Pin it next to the gear for one-tap access
- **Sleep timer** (10, 15, 30, 45, 60 minutes, or end of the video) and **Repeat** for the current video, both in the player menu
- Full-screen immersive player, with cutout/notch-safe controls
- **Double-tap** the left or right side to seek ∓10 seconds, **single tap** to show or hide controls
- Seek bar with elapsed and remaining time, play/pause
- Pauses when you leave the app and resumes when you return
- Plays videos whose files are disguised behind a fake image header (see [Troubleshooting](#-troubleshooting))

### Library
- Videos grouped by folder, with thumbnails, duration, date and size
- **Search** by video or folder name, and **sort** by name, date added, size or length
- **Favorites:** star any video; a **Favorites** section sits at the top of the list
- **Continue watching:** a section at the top for videos you've started, with a progress bar on each thumbnail
- **Long-press a video** for: Play, Add/Remove favorite, Share, Details and Delete (Android asks you to confirm deleting)
- **Fast list:** thumbnails come from Android's own cache and are also saved on disk, and the list only re-reads your media when it actually changed. Video lengths that Android can't read (videos behind a fake image header) are worked out once and remembered, so no more `0:00`
- Includes **SD cards** and other mounted storage
- **Open file** button: pick any video with Android's built-in file picker, even one the media library doesn't list
- "Open with Light Player" from file managers and other apps
- Handles Android 14's "selected videos only" permission, with a one-tap prompt to allow everything

### Audio
- Switch between the video's **embedded audio tracks** (language, channels and codec shown)
- **Add an external audio file** (mp3, m4a, aac, flac, ogg, wav, mka, ac3 and more) and use it instead of the original audio
- One volume slider controls whichever audio is active

### Subtitles and text-to-speech
- **Subtitle sync:** make subtitles earlier or later with −/+ buttons (0.1 s and 0.5 s steps). The spoken track follows, and the offset is remembered per video
- **Subtitle style:** size, text color and background (none, dim, solid), with a live preview. Open it from the player menu or Settings
- **Embedded subtitles:** choose subtitle tracks that are already inside the video file (or turn them off) from the player menu
- **Match and rename subtitle files:** pick a folder and Light Player pairs subtitle files with videos by episode number or name, then renames them to match (Settings → Subtitles)
- **Auto-load subtitles:** when a video starts, Light Player looks for a matching subtitle in the folders you picked and shows it automatically
- Reads **SRT**, **WebVTT** and **ASS/SSA** subtitle files (UTF-8, UTF-16 and Windows-1252 are detected automatically)
- Shows the subtitles over the video
- Generates a **spoken audio track** from the subtitles with the system TTS engine, and plays it with its own volume slider
- Generated tracks are saved to your **Music** folder and listed in the Music tab, so they are only generated once per video and subtitle pair
- **Batch conversion:** in the Music tab, tap **Convert subtitles to audio**, select as many subtitle files as you like, and they are converted one after another with a progress dialog and a summary at the end
- **Never converts twice:** if a subtitle already has audio (made earlier in the player, in the Music tab, or earlier in the same batch), the existing audio is reused instead of being generated again
- Settings for TTS engine, speech rate and pitch

## 📲 Getting started

### Install
1. Open the **Actions** tab of this repository and open the latest successful **Build APK** run.
2. Download the **LightPlayer-APK** artifact and unzip it.
3. Install `app-release.apk` (or `app-debug.apk`) on your phone. You may need to allow *Install unknown apps* for your browser or file manager.

> Requires **Android 10 (API 29)** or newer.
>
> Tagged versions are also published under **Releases** with a ready-to-install APK (see [Release signing](#-release-signing-and-publishing)).

### First launch
Allow access to videos when asked. If you skip this, you can still play anything with the **Open file** button.

## 📖 How to use

| I want to… | Do this |
|---|---|
| Play a video from my library | **Video** tab → tap a folder to expand it → tap a video |
| Play any file on my phone | **Video** tab → **Open file** → choose a video |
| Seek | Double-tap the left or right side to jump 10 seconds, or drag the seek bar |
| Show or hide controls | Tap the screen once |
| Change brightness or volume | Swipe up/down on the left (brightness) or right (volume) side of the video |
| Zoom to fill the screen | Pinch out (pinch in to go back), or gear menu → **Aspect ratio** (Fit / Fill / Stretch / 16:9 / 4:3) |
| Watch in a floating window over other apps | Gear menu → **Floating window** (first time: allow *Display over other apps*) |
| Move / resize the floating window | Drag it to move; pinch, or drag the corner handle, to resize |
| Pause or play in the floating window | Double-tap it |
| Show the −10 / +10, previous / next, expand and close buttons | Tap the floating window once |
| Go to the previous or next video in the floating window | Tap ⏮ / ⏭ (shown when the video came from a folder with more videos) |
| Search the library | Videos tab → 🔍 |
| Sort the library | Videos tab → sort button (name, date, size, length) |
| Favorite, share, delete or see details of a video | Long-press the video |
| Make subtitles earlier or later | Gear menu → **Subtitle sync** |
| Pick an embedded subtitle track | Gear menu → **Subtitle tracks** |
| Change subtitle size, color or background | Gear menu → **Subtitle style** (or Settings → Subtitles) |
| Match and rename subtitle files to their videos | Settings → Subtitles → **Match and rename subtitle files** |
| Lock or change the screen orientation | Gear menu → **Screen rotation** (each tap picks the next mode) |
| Stop playback after a while | Gear menu → **Sleep timer** |
| Open the player menu | Tap the **gear** button at the bottom right of the player (a slim icon rail slides up) |
| See button names and descriptions | Tap the **>** button at the top of the menu rail |
| Pin a menu item next to the gear | Long-press the item in the menu (long-press a pinned button to unpin it) |
| Change playback speed | Gear menu → **Playback speed** |
| Go to the previous or next video | Use the ⏮ ⏭ buttons next to play; the next video also starts automatically |
| Keep audio playing in the background | **Settings** → **Playback** → **Background audio** |
| Switch audio track | Tap the **♪** button → choose a track |
| Add my own audio track | Tap **♪** → **Add audio file…** → choose an audio file |
| Mix or adjust volume | Use the **Audio volume** slider |
| Show subtitles and generate speech | Tap the subtitle button → choose an `.srt`, `.vtt` or `.ass` file |
| Turn the spoken track on or off | Tap the **TTS** pill |
| Convert many subtitle files to audio at once | **Music** tab → **Convert subtitles to audio** → select several subtitle files |
| Find or delete generated audio | **Music** tab (long-press a track to delete it) |
| Change voice, speed or pitch | **Settings** tab |
| Change colors or light/dark mode | **Settings** tab → **Appearance** (mode, color preset, custom hue) |
| Switch between the pill and classic navigation bar | **Settings** tab → **Appearance** → **Navigation style** |

### About added audio tracks
When you add an external audio track, it **replaces** the video's own audio. Switch back by choosing one of the embedded tracks in the **♪** menu. The track follows the video when you seek or pause. If it is shorter than the video, it simply stops at its end.

### About subtitle-to-speech
Speech is generated once, then cached. Audio is matched by the **content of the subtitle** (its lines and timing), not by file name or video, so the same subtitles never get converted twice, whether you load them in the player or convert them in bulk from the Music tab. Audio you already generated with an earlier version of the app is recognised too. Quality and available voices depend on the TTS engine installed on your phone (for example Google Speech Services), which you can pick in **Settings**.

## 🛠 Building from source

**Requirements:** JDK 17 and the Android SDK (platform 34).

```bash
git clone https://github.com/<your-username>/LightPlayer.git
cd LightPlayer
./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew assembleRelease    # APK in app/build/outputs/apk/release/
```

The release build is signed with the debug key, so the APK is always installable without extra setup.

### Automatic builds
Every push runs the workflow in [`.github/workflows/android.yml`](.github/workflows/android.yml), which builds debug and release APKs and uploads them as the **LightPlayer-APK** artifact (kept for 30 days).

## 🔏 Release signing and publishing

By default the release APK is signed with the debug key, which works for installing but changes if you ever build somewhere else. To sign with **your own key** (so every update installs over the last one):

1. Create a keystore once, on any computer or in Termux (`pkg install openjdk-17`), and **keep the file and passwords safe, you can't recover them**:
   ```bash
   keytool -genkeypair -v -keystore lightplayer.jks -alias lightplayer \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Turn it into text: `base64 -w 0 lightplayer.jks` (on macOS: `base64 -i lightplayer.jks`).
3. In your GitHub repository go to **Settings → Secrets and variables → Actions → New repository secret** and add:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | the text from step 2 |
   | `KEYSTORE_PASSWORD` | the keystore password |
   | `KEY_ALIAS` | `lightplayer` (the alias you used) |
   | `KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

4. Push as usual. The workflow log says whether your key was found, and `LightPlayer-APK` now contains an APK signed with it.

**Publishing a release:** push a tag that starts with `v`, for example:

```bash
git tag v1.8 && git push origin v1.8
```

The workflow builds the APK and attaches `LightPlayer-v1.8.apk` to a new GitHub Release with automatic notes. Never commit the keystore file itself.

## 🧱 Tech stack

| | |
|---|---|
| Language | Kotlin |
| UI | Android Views, Material 3 (dark theme), ViewBinding |
| Playback | AndroidX Media3 ExoPlayer |
| Min / target SDK | 29 / 34 |
| Build | Gradle (Kotlin DSL), GitHub Actions |

## 📁 Project structure

```
app/src/main/java/com/lightplayer/
├── MainActivity.kt          # bottom navigation + permissions
├── player/
│   ├── PlayerActivity.kt    # the video player screen
│   ├── SyncedAudio.kt       # extra audio / TTS track kept in sync with the video
│   ├── PlaybackService.kt   # foreground service + notification for background audio
│   ├── PipService.kt        # runs the video behind the floating window
│   ├── PipOverlay.kt        # the floating window itself (drag, resize, taps)
│   └── TsCleanDataSource.kt # plays videos hidden behind fake headers
├── ui/
│   ├── video/               # library, folders, file picker
│   ├── music/               # generated TTS tracks
│   └── settings/            # appearance, file access, TTS and storage settings
├── CrashHandler.kt / CrashActivity.kt   # crash screen (runs in its own process)
├── theme/                   # theme engine (palette, modes, hue) and the floating pill nav bar
├── subtitle/                # SRT / VTT / ASS parsing
├── tts/                     # speech generation and caching
└── util/                    # thumbnails, formatting, preferences, header cleanup,
                             # saved positions, natural sorting
```

## 🩺 Troubleshooting

**A video won't play, or shows an error toast.** The toast includes an error code. Most often the device simply can't decode that codec. Try the video in another player to confirm.

**A video is missing from the list.** The list shows what Android's media library knows about. Use **Open file** to play anything else, and check that you allowed access to *all* videos (not just selected ones).

**Downloaded videos from streaming sites don't play in other apps.** Some sites hide their video streams behind a fake image header (the file starts like a PNG, but the real MPEG-TS video begins a little later). Light Player detects this and skips the junk automatically. The first time you open a large file there may be a few seconds of loading while it scans.

**The duration badge shows 0:00 for a video.** Android's media library couldn't read that file. The player still shows the correct duration once playback starts.

**Deleting generated audio asks for confirmation.** Android only lets an app delete media files it created itself without asking. If it doesn't own a file (for example after reinstalling with a different signing key), Android shows its own "allow deleting?" screen. Files you delete with a file manager disappear from the Music tab automatically, even when Android's media library still lists them.

**The app crashed.** Light Player shows a crash screen with **Copy log** and **Share log** buttons instead of closing silently. You can open the last report any time from **Settings → About → View last crash log**.

**No sound from the generated speech track.** Check that a TTS engine and a voice are installed in your phone's system settings, then pick it in Light Player's **Settings** tab.

## 🔐 Permissions

| Permission | Why |
|---|---|
| `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` | List the videos on your device |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Support Android 14's "selected videos only" mode |
| `READ_MEDIA_AUDIO` | Read generated audio tracks in the Music tab |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playing audio in the background |
| `POST_NOTIFICATIONS` | Show the background-playback notification (Android 13+) |
| `WAKE_LOCK` | Keep playback running with the screen off |
| `SYSTEM_ALERT_WINDOW` | Show the floating window over other apps |

Light Player does not request internet access.

## 🤝 Contributing

Issues and pull requests are welcome. For larger changes, please open an issue first so we can talk it through.

---

<div align="center">

Made with ❤️ for people who just want to watch their videos.

</div>
