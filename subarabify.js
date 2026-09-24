const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const chokidar = require('chokidar');
const { puter } = require('@heyputer/puter.js');

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
  let srt = `1\n00:00:01,000 --> 00:00:04,000\n[ SubArabify — Powered by Puter.js ]\n\n`;
  cues.forEach((cue, idx) => {
    srt += `${idx + 2}\n${cue.start} --> ${cue.end}\n${cue.text}\n\n`;
  });
  return srt;
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
      
      // Zero API key required - Puter AI Gateway handles execution
      const res = await puter.ai.chat(prompt, { model: 'google/gemini-3.8-flash' });
      const arLines = res.toString().trim().split('---').map(l => l.trim());

      chunk.forEach((cue, idx) => {
        translatedCues.push({
          start: cue.start,
          end: cue.end,
          text: arLines[idx] || cue.text
        });
      });
    } catch (err) {
      console.error(`[Puter.js Error] Chunk translation failed:`, err.message);
      return;
    }
  }

  fs.writeFileSync(targetArSrtPath, buildSRT(translatedCues), 'utf8');
  console.log(`[Success] Subtitle saved: ${targetArSrtPath}`);
}

// 3. Puter.js Audio-to-Subtitle Fallback
async function transcribeAudioWithPuter(videoPath, targetArSrtPath) {
  console.log(`[FFmpeg] No eng.srt found. Extracting audio from ${path.basename(videoPath)}...`);
  const tempWav = path.join('/tmp', `temp_${Date.now()}.wav`);

  try {
    execSync(`ffmpeg -y -i "${videoPath}" -vn -ar 16000 -ac 1 "${tempWav}"`, { stdio: 'ignore' });

    console.log(`[Puter.js] Transcribing & translating audio...`);
    const result = await puter.ai.speech2txt({
      file: tempWav,
      model: 'whisper-1',
      translate: true
    });

    if (fs.existsSync(tempWav)) fs.unlinkSync(tempWav);

    const singleCue = [{ start: '00:00:05,000', end: '00:00:15,000', text: result.text || result }];
    fs.writeFileSync(targetArSrtPath, buildSRT(singleCue), 'utf8');
    console.log(`[Success] Transcription saved: ${targetArSrtPath}`);
  } catch (err) {
    console.error(`[Puter Speech Error]`, err.message);
    if (fs.existsSync(tempWav)) fs.unlinkSync(tempWav);
  }
}

// 4. File Processor & Folder Monitor
async function processVideoFile(videoPath) {
  const dir = path.dirname(videoPath);
  const ext = path.extname(videoPath);
  const baseName = path.basename(videoPath, ext);
  const arSrtPath = path.join(dir, `${baseName}.SubArabify.ar.srt`);

  if (fs.existsSync(arSrtPath)) return;

  const engSrtPath = path.join(dir, 'eng.srt');
  const namedSrtPath = path.join(dir, `${baseName}.srt`);
  const sourceSrt = fs.existsSync(engSrtPath) ? engSrtPath : (fs.existsSync(namedSrtPath) ? namedSrtPath : null);

  if (sourceSrt) {
    await translateSRTWithPuter(sourceSrt, arSrtPath);
  } else {
    await transcribeAudioWithPuter(videoPath, arSrtPath);
  }
}

const watcher = chokidar.watch(MEDIA_DIR, { persistent: true, depth: 3 });
watcher.on('add', filePath => {
  if (['.mp4', '.mkv', '.avi', '.m4v'].includes(path.extname(filePath).toLowerCase())) {
    processVideoFile(filePath);
  }
});
