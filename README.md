# SubArabify

SubArabify is an automated background translation engine designed to translate and brand movie subtitles entirely on-device, with zero backend infrastructure. It leverages the cutting-edge power of [Puter.js](https://puter.com/) to process audio and subtitles effortlessly using keyless, serverless AI generation directly on your device.

By relying on Puter's "User-Pays" and keyless model, SubArabify requires **no API keys, no Google Cloud configurations, and no complex backend deployments**. It works entirely locally—even directly on your smartphone through Termux.

## Features

- **Keyless AI Translation Engine:** Translates existing `.srt` files or extracts and transcribes audio directly using `Puter.js` AI Gateway.
- **Background Automation:** Continuously watches your media directory for new movies and processes them transparently.
- **Smart Branding:** Watermarks output subtitles with `[ SubArabify — Powered by Puter.js ]` and writes them as `MovieName.SubArabify.ar.srt`.
- **Runs Everywhere:** Designed to run flawlessly on Termux (Android) or any standard Node.js environment.

---

## 🚀 Getting Started on Termux (Android)

You can run the SubArabify engine directly on your Android phone using standard Node.js tools in Termux.

### 1. Grant Storage Access
Allow Termux to read and write to your phone's media storage:
```bash
termux-setup-storage
```

### 2. Install Dependencies (Node.js & FFmpeg)
Install Node.js to power the `Puter.js` scripts, and FFmpeg for local audio extraction:
```bash
pkg update && pkg install nodejs ffmpeg -y
```

### 3. Setup Project
Clone the repository and install the NPM packages:
```bash
git clone https://github.com/muxd22-alt/SubArabify.git
cd SubArabify
npm install
```

### 4. Run SubArabify!
Run the node script and point it to your phone's movie folder:
```bash
npm start -- --media ~/storage/shared/Movies
```
Or run directly:
```bash
node subarabify.js --media ~/storage/shared/Movies
```

The script will now actively watch the destination folder. Whenever a `.mp4`, `.mkv`, or `.avi` file is added, SubArabify will scan for an existing `.srt`. If it's missing, FFmpeg will quickly extract the audio track to a temporary WAV, which is then flawlessly transcribed and translated into an Arabic `.srt` subtitle in the background!

## Dependencies

- **Node.js** (v14+)
- **FFmpeg** (v4.0+)
- **@heyputer/puter.js**: Enables completely free and powerful API translations without needing an API Key.
- **chokidar**: Facilitates efficient local folder monitoring.

---
*Created by [the SubArabify community](https://github.com/muxd22-alt).*