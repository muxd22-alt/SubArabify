const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const chokidar = require('chokidar');

// Auto-update check
try {
    console.log('[SubArabify] \ud83d\udd04 Checking for updates from GitHub...');
    execSync('git pull --rebase', { stdio: 'inherit', cwd: __dirname });
    console.log('[SubArabify] \u2705 Up to date!');
} catch (e) {
    console.log('[SubArabify] \u26a0\ufe0f Note: Could not auto-update from git. Skipping.');
}

const PUTER_TOKEN = process.env.PUTER_AUTH_TOKEN || '';

let puter;

// Parse --media folder argument (defaults to /sdcard/Movies)
const args = process.argv.slice(2);
const mediaIdx = args.indexOf('--media');
const MEDIA_DIR = mediaIdx !== -1 ? args[mediaIdx + 1] : '/sdcard/Movies';

console.log(`[SubArabify Puter] Active and watching: ${MEDIA_DIR}`);

// 1. SRT Parser and Builder
function parseSRT(data) {
  const pattern = /(\d+)\r?\n(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})\r?\n([\s\S]*?)(?=\r?\n\r?\n|\r?\n*$)/g;
  const result = [];
  let match;
  while ((match = pattern.exec(data)) !== null) {
    result.push({
      index: match[1],
      start: match[2],
      end: match[3],
      text: match[4].trim()
    });
  }
  return result;
}

function buildSRT(cues) {
  let srt = `1\n00:00:01,000 --> 00:00:04,000\n[ \u062a\u0631\u062c\u0645\u062a \u0627\u0644\u0623\u062f\u0627\u0629 \u0633\u0627\u0628 \u0623\u0631\u0627\u0628\u064a\u0641\u0627\u064a \u2014 \u0645\u062f\u0639\u0648\u0645 \u0645\u0646 Puter.js ]\n\n`;
  cues.forEach((cue, idx) => {
    srt += `${idx + 2}\n${cue.start} --> ${cue.end}\n${cue.text}\n\n`;
  });
  return srt;
}

// Helper: Retries a function up to maxRetries times
async function withRetry(fn, maxRetries = 3, contextMsg = "") {
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      console.error(`[Puter Error] ${contextMsg} Attempt ${attempt}/${maxRetries} failed: ${err.message}`);
      if (attempt === maxRetries) throw err;
      await new Promise(r => setTimeout(r, 2000 * attempt)); // Exponential backoff
    }
  }
}

// 2. Puter.js Keyless Translation Logic
async function translateSRTWithPuter(engSrtPath, targetArSrtPath) {
  console.log(`[Puter.js] Parsing ${path.basename(engSrtPath)}...`);
  const rawData = fs.readFileSync(engSrtPath, 'utf8');
  const cues = parseSRT(rawData);

  if (cues.length === 0) return;

  const chunkSize = 35;
  const translatedCues = [];

  for (let i = 0; i < cues.length; i += chunkSize) {
    const chunk = cues.slice(i, i + chunkSize);
    const textChunk = chunk.map(c => c.text).join('\n---\n');

    const prompt = `You are a professional subtitle translator. Translate these English lines to natural Arabic.
CRITICAL: Preserve the exact number of blocks separated by '---'. Output ONLY the translated Arabic blocks separated by '---'. No notes, no markdown.

${textChunk}`;

    try {
      console.log(`[Puter.js] Translating cues ${i + 1} to ${Math.min(i + chunkSize, cues.length)} of ${cues.length}...`);
      
      const res = await withRetry(
          () => puter.ai.chat(prompt, { model: 'google/gemini-3.8-flash' }),
          3,
          "Translation chunk"
      );
      
      const arLines = res.toString().trim().split('---').map(l => l.trim());

      chunk.forEach((cue, idx) => {
        translatedCues.push({
          start: cue.start,
          end: cue.end,
          text: arLines[idx] || cue.text
        });
      });
    } catch (err) {
      console.error(`[Fatal] Chunk translation permanently failed. Skipping file.`);
      return;
    }
  }

  fs.writeFileSync(targetArSrtPath, buildSRT(translatedCues), 'utf8');
  console.log(`[Success] Subtitle saved: ${targetArSrtPath}`);
}

// Timestamp helpers for chunk offset merging
function timeToMs(t) {
  const [hms, ms] = t.trim().split(',');
  const [h, m, s] = hms.split(':').map(Number);
  return (h * 3600 + m * 60 + s) * 1000 + Number(ms);
}
function msToTime(d) {
  const ms = d % 1000;
  const s = Math.floor((d / 1000) % 60);
  const m = Math.floor((d / (1000 * 60)) % 60);
  const h = Math.floor(d / (1000 * 60 * 60));
  return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')},${String(ms).padStart(3,'0')}`;
}

// 3. Puter.js Audio-to-Subtitle Fallback (chunked for 25MB limit)
async function transcribeAudioWithPuter(videoPath, targetArSrtPath) {
  console.log(`[FFmpeg] No matching source .srt found. Extracting & chunking audio from ${path.basename(videoPath)}...`);

  const tmpFolder = path.join(path.dirname(videoPath), '.subarabify_tmp');
  if (!fs.existsSync(tmpFolder)) fs.mkdirSync(tmpFolder);

  // Split audio into 30-minute compressed MP3 chunks (~15MB each, well under 25MB)
  const stamp = Date.now();
  const chunkPattern = path.join(tmpFolder, `chunk_${stamp}_%03d.mp3`);
  const SEGMENT_SECS = 1800; // 30 minutes

  try {
    execSync(`ffmpeg -y -i "${videoPath}" -vn -c:a libmp3lame -b:a 32k -f segment -segment_time ${SEGMENT_SECS} "${chunkPattern}"`, { stdio: 'ignore' });

    const chunkFiles = fs.readdirSync(tmpFolder)
      .filter(f => f.startsWith(`chunk_${stamp}_`))
      .sort();

    console.log(`[Puter.js] Split into ${chunkFiles.length} audio chunk(s). Transcribing...`);

    let allCues = [];

    for (let i = 0; i < chunkFiles.length; i++) {
      const chunkPath = path.join(tmpFolder, chunkFiles[i]);
      console.log(`[Puter.js] Transcribing chunk ${i + 1}/${chunkFiles.length}...`);

      try {
        const result = await withRetry(
          () => {
            const audioData = fs.readFileSync(chunkPath);
            const audioDataUri = `data:audio/mp3;base64,${audioData.toString('base64')}`;
            return puter.ai.speech2txt({ file: audioDataUri, model: 'whisper-1', translate: true });
          },
          2,
          `Chunk ${i + 1}`
        );

        // Offset timestamps by chunk position
        const offsetMs = i * SEGMENT_SECS * 1000;
        const rawText = result.text || String(result);

        if (rawText && rawText.trim().length > 0) {
          // Whisper returns plain text; create evenly-spaced subtitle cues
          const words = rawText.trim().split(/\s+/);
          const wordsPerCue = 12;
          const totalCuesInChunk = Math.ceil(words.length / wordsPerCue);
          const cueDuration = Math.floor((SEGMENT_SECS * 1000) / Math.max(totalCuesInChunk, 1));

          for (let c = 0; c < totalCuesInChunk; c++) {
            const cueWords = words.slice(c * wordsPerCue, (c + 1) * wordsPerCue).join(' ');
            const cueStart = offsetMs + c * cueDuration;
            const cueEnd = Math.min(cueStart + cueDuration - 100, offsetMs + SEGMENT_SECS * 1000);
            allCues.push({
              start: msToTime(cueStart),
              end: msToTime(cueEnd),
              text: cueWords
            });
          }
        }
      } catch (chunkErr) {
        console.error(`[Puter Error] Chunk ${i + 1} permanently failed. Skipping this chunk.`);
      }

      // Clean up chunk file immediately to save phone storage
      try { fs.unlinkSync(chunkPath); } catch(e) {}
    }

    if (allCues.length > 0) {
      fs.writeFileSync(targetArSrtPath, buildSRT(allCues), 'utf8');
      console.log(`[Success] Transcription saved (${allCues.length} cues): ${targetArSrtPath}`);
    } else {
      console.error(`[Puter Speech Error] No speech detected in any chunk.`);
    }

  } catch (err) {
    console.error(`[Puter Speech Error] Fatal error in transcription:`, err.message);
    // Cleanup any leftover chunk files
    try {
      fs.readdirSync(tmpFolder)
        .filter(f => f.startsWith(`chunk_${stamp}_`))
        .forEach(f => { try { fs.unlinkSync(path.join(tmpFolder, f)); } catch(e) {} });
    } catch(e) {}
  }
}

// 4. File Processor & Folder Monitor
async function processVideoFile(videoPath) {
  // Prevent parallel processing anomalies when many files drop at once
  await new Promise(r => setTimeout(r, 1000));
  
  const dir = path.dirname(videoPath);
  const ext = path.extname(videoPath);
  const baseName = path.basename(videoPath, ext);
  const arSrtPath = path.join(dir, `${baseName}.SubArabify.ar.srt`);

  if (fs.existsSync(arSrtPath)) return;

  // Search logic designed for TV episodes (match exact name first)
  const targetedSrtPath = path.join(dir, `${baseName}.srt`);
  const engSrtPath = path.join(dir, 'eng.srt');
  
  const sourceSrt = fs.existsSync(targetedSrtPath) 
                      ? targetedSrtPath 
                      : (fs.existsSync(engSrtPath) ? engSrtPath : null);

  if (sourceSrt) {
    await translateSRTWithPuter(sourceSrt, arSrtPath);
  } else {
    await transcribeAudioWithPuter(videoPath, arSrtPath);
  }
}

// Initial full-scan on boot + active watching
async function start() {
    try {
        const puterModule = await import('@heyputer/puter.js');
        puter = puterModule.default || puterModule;
        
        if (PUTER_TOKEN) {
            puter.setAuthToken(PUTER_TOKEN);
        } else {
            console.log('[SubArabify] \u26a0\ufe0f  \u0644\u0645 \u064a\u062a\u0645 \u062a\u0639\u064a\u064a\u0646 PUTER_AUTH_TOKEN \u2014 \u0633\u064a\u062a\u0645 \u0645\u062d\u0627\u0648\u0644\u0629 \u0627\u0644\u0645\u0635\u0627\u062f\u0642\u0629 \u0627\u0644\u062a\u0644\u0642\u0627\u0626\u064a\u0629');
        }
    } catch (e) {
        console.error('[SubArabify] \u274c \u062e\u0637\u0623 \u0641\u064a \u062a\u0647\u064a\u0626\u0629 Puter.js:', e.message);
        console.log('[SubArabify] \ud83d\udca1 \u062a\u0623\u0643\u062f \u0645\u0646 \u062a\u062b\u0628\u064a\u062a \u0627\u0644\u062d\u0632\u0645: npm install');
        process.exit(1);
    }

    const watcher = chokidar.watch(MEDIA_DIR, { persistent: true, depth: 4, awaitWriteFinish: true });
    watcher.on('add', filePath => {
      if (['.mp4', '.mkv', '.avi', '.m4v'].includes(path.extname(filePath).toLowerCase())) {
        processVideoFile(filePath).catch(e => console.error(e));
      }
    });
}

start();
