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

### Playback
- Powered by **Media3 ExoPlayer**: wide format and codec support, with automatic decoder fallback
- Full-screen immersive player, with cutout/notch-safe controls
- **Double-tap** the left or right side to seek ∓10 seconds, **single tap** to show or hide controls
- ±10s buttons, seek bar with elapsed and remaining time, play/pause
- Pauses when you leave the app and resumes when you return
- Plays videos whose files are disguised behind a fake image header (see [Troubleshooting](#-troubleshooting))

### Library
- Videos grouped by folder, with thumbnails, duration, date and size
- Includes **SD cards** and other mounted storage
- **Open file** button: pick any video with Android's built-in file picker, even one the media library doesn't list
- "Open with Light Player" from file managers and other apps
- Handles Android 14's "selected videos only" permission, with a one-tap prompt to allow everything

### Audio
- Switch between the video's **embedded audio tracks** (language, channels and codec shown)
- **Add an external audio file** (mp3, m4a, aac, flac, ogg, wav, mka, ac3 and more) and use it instead of the original audio
- One volume slider controls whichever audio is active

### Subtitles and text-to-speech
- Reads **SRT**, **WebVTT** and **ASS/SSA** subtitle files (UTF-8, UTF-16 and Windows-1252 are detected automatically)
- Shows the subtitles over the video
- Generates a **spoken audio track** from the subtitles with the system TTS engine, and plays it with its own volume slider
- Generated tracks are saved to your **Music** folder and listed in the Music tab, so they are only generated once per video and subtitle pair
- Settings for TTS engine, speech rate and pitch

## 📲 Getting started

### Install
1. Open the **Actions** tab of this repository and open the latest successful **Build APK** run.
2. Download the **LightPlayer-APK** artifact and unzip it.
3. Install `app-release.apk` (or `app-debug.apk`) on your phone. You may need to allow *Install unknown apps* for your browser or file manager.

> Requires **Android 10 (API 29)** or newer.

### First launch
Allow access to videos when asked. If you skip this, you can still play anything with the **Open file** button.

## 📖 How to use

| I want to… | Do this |
|---|---|
| Play a video from my library | **Video** tab → tap a folder to expand it → tap a video |
| Play any file on my phone | **Video** tab → **Open file** → choose a video |
| Seek | Double-tap left or right side, use the ±10s buttons, or drag the seek bar |
| Show or hide controls | Tap the screen once |
| Switch audio track | Tap the **♪** button → choose a track |
| Add my own audio track | Tap **♪** → **Add audio file…** → choose an audio file |
| Mix or adjust volume | Use the **Audio volume** slider |
| Show subtitles and generate speech | Tap the subtitle button → choose an `.srt`, `.vtt` or `.ass` file |
| Turn the spoken track on or off | Tap the **TTS** pill |
| Find or delete generated audio | **Music** tab |
| Change voice, speed or pitch | **Settings** tab |

### About added audio tracks
When you add an external audio track, it **replaces** the video's own audio. Switch back by choosing one of the embedded tracks in the **♪** menu. The track follows the video when you seek or pause. If it is shorter than the video, it simply stops at its end.

### About subtitle-to-speech
Speech is generated once, then cached. Opening the same video with the same subtitle file loads the saved track instantly. Quality and available voices depend on the TTS engine installed on your phone (for example Google Speech Services), which you can pick in **Settings**.

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
│   └── TsCleanDataSource.kt # plays videos hidden behind fake headers
├── ui/
│   ├── video/               # library, folders, file picker
│   ├── music/               # generated TTS tracks
│   └── settings/            # TTS and storage settings
├── subtitle/                # SRT / VTT / ASS parsing
├── tts/                     # speech generation and caching
└── util/                    # thumbnails, formatting, preferences, header cleanup
```

## 🩺 Troubleshooting

**A video won't play, or shows an error toast.** The toast includes an error code. Most often the device simply can't decode that codec. Try the video in another player to confirm.

**A video is missing from the list.** The list shows what Android's media library knows about. Use **Open file** to play anything else, and check that you allowed access to *all* videos (not just selected ones).

**Downloaded videos from streaming sites don't play in other apps.** Some sites hide their video streams behind a fake image header (the file starts like a PNG, but the real MPEG-TS video begins a little later). Light Player detects this and skips the junk automatically. The first time you open a large file there may be a few seconds of loading while it scans.

**The duration badge shows 0:00 for a video.** Android's media library couldn't read that file. The player still shows the correct duration once playback starts.

**No sound from the generated speech track.** Check that a TTS engine and a voice are installed in your phone's system settings, then pick it in Light Player's **Settings** tab.

## 🔐 Permissions

| Permission | Why |
|---|---|
| `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` | List the videos on your device |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Support Android 14's "selected videos only" mode |
| `READ_MEDIA_AUDIO` | Read generated audio tracks in the Music tab |

Light Player does not request internet access.

## 🤝 Contributing

Issues and pull requests are welcome. For larger changes, please open an issue first so we can talk it through.

---

<div align="center">

Made with ❤️ for people who just want to watch their videos.

</div>
