# SubArabify 🎬

**Automatic Arabic Subtitles — On-Device, Offline, Privacy-First.**

SubArabify monitors your movie and TV show folders and automatically translates English `.srt` subtitles to Arabic using Google ML Kit's on-device translation model. No data is ever sent to the cloud.

---

## Features

| Feature | Description |
|---|---|
| 🔒 **100% Offline** | ML Kit runs entirely on your phone — no internet needed after initial model download |
| ⚡ **Background Monitoring** | WorkManager periodically scans your media folders for new content |
| 🎯 **Smart Detection** | Finds videos missing Arabic subs and auto-creates `.ar.srt` files |
| ✏️ **Thmanyah Sans** | Subtitles are tagged with the premium Thmanyah Sans typeface |

## Architecture

```
app/src/main/java/com/subarabify/
├── data/
│   ├── SrtParser.kt          # SRT block parser & Arabic subtitle builder
│   └── StorageHelper.kt      # SAF folder scanner utility
├── engine/
│   ├── MlKitTranslator.kt    # Offline English→Arabic ML Kit engine
│   └── WhisperEngine.kt      # whisper.cpp native transcription bridge (stub)
├── ui/
│   ├── MainActivity.kt       # Jetpack Compose premium dashboard
│   └── theme/
│       ├── Color.kt           # Dark gold/amber color palette
│       ├── Theme.kt           # Material3 dark theme
│       └── Type.kt            # Thmanyah Sans font family
└── worker/
    └── SubArabifyWorker.kt    # WorkManager background auto-checker
```

## How It Works

1. **Select** your Movies / TV Shows folder via Android's Storage Access Framework
2. **Enable** background monitoring with your desired scan interval (15 min – 6 hours)
3. **Relax** — SubArabify finds English `.srt` files, translates them, and saves `.ar.srt` next to your videos

## Building

```bash
gradle assembleRelease --no-daemon
```

The APK will be at `app/build/outputs/apk/release/`.

## CI/CD

Every push to `main` triggers a GitHub Actions workflow that:
1. Builds the release APK
2. Deploys the APK + a landing page to GitHub Pages

## License

Open-source. Privacy-first. On-device.
