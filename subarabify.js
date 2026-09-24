const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const chokidar = require('chokidar');

// Global error handlers to prevent socket/fetch terminations from abruptly stopping the daemon
process.on('unhandledRejection', (reason) => {
    console.error('[SubArabify Warning] Unhandled Rejection:', reason);
});

process.on('uncaughtException', (err) => {
    console.error('[SubArabify Warning] Uncaught Exception:', err);
});

// Polyfill patch for @heyputer/puter.js/src/lib/polyfills/xhrshim.js
// Prevents TypeError when server response headers miss 'content-type'
if (typeof globalThis.Headers !== 'undefined' && globalThis.Headers.prototype && globalThis.Headers.prototype.get) {
    const origGet = globalThis.Headers.prototype.get;
    globalThis.Headers.prototype.get = function(name) {
        const val = origGet.call(this, name);
        if (val === null && typeof name === 'string' && name.toLowerCase() === 'content-type') {
            return '';
        }
        return val;
    };
}

const PUTER_TOKEN = process.env.PUTER_AUTH_TOKEN || '';

let puter;

// Parse --media folder argument (defaults to /sdcard/Movies)
const args = process.argv.slice(2);
const mediaIdx = args.indexOf('--media');
const MEDIA_DIR = mediaIdx !== -1 ? args[mediaIdx + 1] : '/sdcard/Movies';

// Sequential Processing Queue
const fileQueue = [];
const processingFiles = new Set();
let isProcessingQueue = false;
let videoProcessor = processVideoFile;

function setVideoProcessor(fn) {
    videoProcessor = fn;
}

async function processQueue() {
    if (isProcessingQueue) return;
    isProcessingQueue = true;

    while (fileQueue.length > 0) {
        const filePath = fileQueue.shift();
        console.log(`[Queue] Processing file (${fileQueue.length} remaining): ${path.basename(filePath)}`);
        try {
            await videoProcessor(filePath);
        } catch (err) {
            console.error(`[Queue Error] Error processing ${path.basename(filePath)}:`, err.message || err);
        } finally {
            processingFiles.delete(filePath);
        }
    }

    isProcessingQueue = false;
}

function enqueueFile(filePath) {
    const ext = path.extname(filePath).toLowerCase();
    if (!['.mp4', '.mkv', '.avi', '.m4v'].includes(ext)) return false;

    const dir = path.dirname(filePath);
    const baseName = path.basename(filePath, ext);
    const arSrtPath = path.join(dir, `${baseName}.SubArabify.ar.srt`);

    if (fs.existsSync(arSrtPath)) return false;
    if (processingFiles.has(filePath)) return false;

    processingFiles.add(filePath);
    fileQueue.push(filePath);
    console.log(`[Queue] Enqueued: ${path.basename(filePath)} (Total queued: ${fileQueue.length})`);
    processQueue().catch(e => console.error('[Queue Error]', e));
    return true;
}

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
  let srt = `1\n00:00:01,000 --> 00:00:04,000\n[ ترجمت الأداة ساب أرابيفاي — مدعوم من Puter.js ]\n\n`;
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

// Helper to extract text safely from Puter API response
function extractResponseText(res) {
  if (!res) return '';
  if (typeof res === 'string') return res;
  if (res.text && typeof res.text === 'string') return res.text;
  if (res.message && res.message.content) {
    if (typeof res.message.content === 'string') return res.message.content;
    if (Array.isArray(res.message.content)) {
      return res.message.content.map(c => typeof c === 'string' ? c : (c.text || '')).join('');
    }
  }
  return String(res);
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
      
      const resText = extractResponseText(res);
      const arLines = resText.trim().split('---').map(l => l.trim());

      chunk.forEach((cue, idx) => {
        translatedCues.push({
          start: cue.start,
          end: cue.end,
          text: arLines[idx] || cue.text
        });
      });
    } catch (err) {
      console.error(`[Fatal] Chunk translation permanently failed. Skipping file: ${err.message}`);
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
        const rawText = extractResponseText(result);

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
    // Auto-update check
    try {
        console.log('[SubArabify] 🔄 Checking for updates from GitHub...');
        execSync('git pull --rebase', { stdio: 'inherit', cwd: __dirname });
        console.log('[SubArabify] ✅ Up to date!');
    } catch (e) {
        console.log('[SubArabify] ⚠️ Note: Could not auto-update from git. Skipping.');
    }

    console.log(`[SubArabify Puter] Active and watching: ${MEDIA_DIR}`);

    try {
        const puterModule = await import('@heyputer/puter.js');
        puter = puterModule.default || puterModule;
        
        if (PUTER_TOKEN) {
            puter.setAuthToken(PUTER_TOKEN);
        } else {
            console.log('[SubArabify] ⚠️  لم يتم تعيين PUTER_AUTH_TOKEN — سيتم محاولة المصادقة التلقائية');
        }
    } catch (e) {
        console.error('[SubArabify] ❌ خطأ في تهيئة Puter.js:', e.message);
        console.log('[SubArabify] 💡 تأكد من تثبيت الحزم: npm install');
        process.exit(1);
    }

    const watcher = chokidar.watch(MEDIA_DIR, { persistent: true, depth: 4, awaitWriteFinish: true });
    watcher.on('add', filePath => {
      enqueueFile(filePath);
    });
}

if (require.main === module) {
    start();
}

module.exports = {
    parseSRT,
    buildSRT,
    withRetry,
    extractResponseText,
    timeToMs,
    msToTime,
    processVideoFile,
    setVideoProcessor,
    enqueueFile,
    processQueue,
    fileQueue,
    processingFiles,
    start
};
