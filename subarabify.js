const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const chokidar = require('chokidar');

// Puter.js Node.js initialization
// Get auth token from: https://puter.com/dashboard
// Set via: export PUTER_AUTH_TOKEN=your_token_here
const { init } = require('@heyputer/puter.js/src/init.cjs');
const PUTER_TOKEN = process.env.PUTER_AUTH_TOKEN || '';

let puter;
try {
    puter = init(PUTER_TOKEN);
    if (!PUTER_TOKEN) {
        console.log('[SubArabify] ⚠️  لم يتم تعيين PUTER_AUTH_TOKEN — سيتم محاولة المصادقة التلقائية');
    }
} catch (e) {
    console.error('[SubArabify] ❌ خطأ في تهيئة Puter.js:', e.message);
    console.log('[SubArabify] 💡 احصل على مفتاح API من: https://puter.com/dashboard');
    console.log('[SubArabify] 💡 ثم شغل: export PUTER_AUTH_TOKEN=your_token');
    process.exit(1);
}

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

const os = require('os');

// 3. Puter.js Audio-to-Subtitle Fallback
async function transcribeAudioWithPuter(videoPath, targetArSrtPath) {
  console.log(`[FFmpeg] No matching source .srt found. Extracting audio from ${path.basename(videoPath)}...`);
  
  // Use a local tmp folder in the media directory instead of relying on os.tmpdir() which fails in Termux
  const tmpFolder = path.join(path.dirname(videoPath), '.subarabify_tmp');
  if (!fs.existsSync(tmpFolder)) fs.mkdirSync(tmpFolder);
  const tempWav = path.join(tmpFolder, `temp_${Date.now()}.wav`);

  try {
    execSync(`ffmpeg -y -i "${videoPath}" -vn -ar 16000 -ac 1 "${tempWav}"`, { stdio: 'ignore' });

    console.log(`[Puter.js] Transcribing & translating audio...`);
    
    const result = await withRetry(
      () => puter.ai.speech2txt({ file: tempWav, model: 'whisper-1', translate: true }),
      2,
      "Speech-to-text"
    );

    if (fs.existsSync(tempWav)) fs.unlinkSync(tempWav);

    const singleCue = [{ start: '00:00:05,000', end: '00:00:15,000', text: result.text || result }];
    fs.writeFileSync(targetArSrtPath, buildSRT(singleCue), 'utf8');
    console.log(`[Success] Transcription saved: ${targetArSrtPath}`);
  } catch (err) {
    console.error(`[Puter Speech Error] Fatal error in transcription.`);
    if (fs.existsSync(tempWav)) fs.unlinkSync(tempWav);
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
const watcher = chokidar.watch(MEDIA_DIR, { persistent: true, depth: 4, awaitWriteFinish: true });
watcher.on('add', filePath => {
  if (['.mp4', '.mkv', '.avi', '.m4v'].includes(path.extname(filePath).toLowerCase())) {
    processVideoFile(filePath).catch(e => console.error(e));
  }
});
