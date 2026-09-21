# SubArabify

**Automatic Arabic subtitles on your phone — offline, folder-first, privacy-first.**

Pick a media folder. SubArabify finds English `.srt` files next to your videos, translates them on-device with ML Kit, and writes a player-ready Arabic file beside each video.

---

## Download

| | |
|---|---|
| **Latest APK** | [SubArabify-latest.apk](https://muxd22-alt.github.io/SubArabify/SubArabify-latest.apk) (~29 MB) |
| **Landing page** | [muxd22-alt.github.io/SubArabify](https://muxd22-alt.github.io/SubArabify/) |
| **Source** | [github.com/muxd22-alt/SubArabify](https://github.com/muxd22-alt/SubArabify) |

Built automatically on every push to `main` and published to GitHub Pages (same APK the site’s Download button serves).

> Requires Android. Install from unknown sources / your file manager. Current builds are signed with the debug keystore (super-beta).

---

## What it actually does

1. **Select a folder** — Movies, Downloads, or any tree via Android Storage Access Framework  
2. **Translate** — scans for videos that already have an English `.srt` (`.srt`, `.en.srt`, `.eng.srt`, `.English.srt`)  
3. **Write on device** — saves `MovieName.SubArabify.ar.srt` next to the video so MX Player, VLC, and similar apps can load it  

Videos with no English subtitle stay **pending** (on-device speech-to-text is not wired yet).

### Subtitle branding (honest rules)

| Where | What |
|---|---|
| **Start** | Short `SubArabify` cue near the opening |
| **Middle** | Free — dialogue only, no watermark |
| **End** | Short `SubArabify` cue after the last line |
| **Filename** | Always `*.SubArabify.ar.srt` |
| **Inside the file** | A hidden `NOTE` for anyone who opens the `.srt` in a text editor |

---

## Features

| Feature | Reality |
|---|---|
| Offline translation | ML Kit EN→AR on device after the model downloads once |
| Background scans | WorkManager at your interval (15 min – 6 hours) |
| Smart skip | Already has `.SubArabify.ar.srt` → skip; user skip list supported |
| Arabic styling | Lines tagged with Thmanyah Sans (renders if the player has the font) |

## Architecture

```
app/src/main/java/com/subarabify/
├── data/
│   ├── SrtParser.kt          # Parse + branded Arabic SRT builder
│   └── StorageHelper.kt      # SAF helpers
├── engine/
│   ├── MlKitTranslator.kt    # Offline English→Arabic
│   └── WhisperEngine.kt      # Transcription stub (not active yet)
├── ui/
│   └── MainActivity.kt       # Folder pick, monitoring, status
└── worker/
    └── SubArabifyWorker.kt   # Folder scan → translate → write .srt
```

## Building locally

```bash
gradle assembleRelease --no-daemon
```

APK output: `app/build/outputs/apk/release/`

## CI/CD

Push to `main` → GitHub Actions builds release APK → deploys `docs/index.html` + `SubArabify-latest.apk` to GitHub Pages.

## License

Open-source. Privacy-first. On-device.
