const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const os = require('os');
const {
    parseSRT,
    buildSRT,
    withRetry,
    extractResponseText,
    timeToMs,
    msToTime,
    enqueueFile,
    fileQueue,
    processingFiles,
    setVideoProcessor,
    processVideoFile
} = require('../subarabify.js');

test('parseSRT and buildSRT', () => {
    const sampleSRT = `1\n00:00:01,000 --> 00:00:04,000\nHello World\n\n2\n00:00:05,000 --> 00:00:08,000\nSecond cue text`;
    const cues = parseSRT(sampleSRT);

    assert.equal(cues.length, 2);
    assert.equal(cues[0].text, 'Hello World');
    assert.equal(cues[0].start, '00:00:01,000');
    assert.equal(cues[0].end, '00:00:04,000');
    assert.equal(cues[1].text, 'Second cue text');

    const built = buildSRT(cues);
    assert.ok(built.includes('[ ترجمت الأداة ساب أرابيفاي — مدعوم من Puter.js ]'));
    assert.ok(built.includes('Hello World'));
    assert.ok(built.includes('Second cue text'));
});

test('timeToMs and msToTime conversions', () => {
    const timeStr = '01:02:03,456';
    const ms = timeToMs(timeStr);
    assert.equal(ms, (1 * 3600 + 2 * 60 + 3) * 1000 + 456);
    assert.equal(msToTime(ms), timeStr);
});

test('extractResponseText formats', () => {
    assert.equal(extractResponseText('plain text'), 'plain text');
    assert.equal(extractResponseText({ text: 'object text' }), 'object text');
    assert.equal(extractResponseText({ message: { content: 'message text' } }), 'message text');
    assert.equal(extractResponseText({ message: { content: [{ text: 'part 1 ' }, 'part 2'] } }), 'part 1 part 2');
    assert.equal(extractResponseText(null), '');
});

test('withRetry handles success and retries', async () => {
    let attempts = 0;
    const successFn = async () => {
        attempts++;
        if (attempts < 2) throw new Error('Temporary failure');
        return 'success';
    };

    const res = await withRetry(successFn, 3, 'Test retry');
    assert.equal(res, 'success');
    assert.equal(attempts, 2);
});

test('withRetry throws after max retries', async () => {
    const failFn = async () => {
        throw new Error('Persistent failure');
    };

    await assert.rejects(
        async () => await withRetry(failFn, 2, 'Test fail'),
        { message: 'Persistent failure' }
    );
});

test('enqueueFile validates file types and manages queue', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'subarabify_test_'));
    const dummyMp4 = path.join(tmpDir, 'test_movie.mp4');
    const dummyTxt = path.join(tmpDir, 'notes.txt');
    const processedMp4 = path.join(tmpDir, 'processed_movie.mp4');
    const existingArSrt = path.join(tmpDir, 'processed_movie.SubArabify.ar.srt');

    fs.writeFileSync(dummyMp4, 'fake video');
    fs.writeFileSync(dummyTxt, 'text file');
    fs.writeFileSync(processedMp4, 'fake video');
    fs.writeFileSync(existingArSrt, 'existing srt');

    const processedList = [];
    setVideoProcessor(async (file) => {
        processedList.push(file);
    });

    // Reset queue state
    fileQueue.length = 0;
    processingFiles.clear();

    // Invalid extension
    assert.equal(enqueueFile(dummyTxt), false);

    // Existing subtitle already exists
    assert.equal(enqueueFile(processedMp4), false);

    // Valid file enqueued
    assert.equal(enqueueFile(dummyMp4), true);

    // Wait for queue loop to complete
    await new Promise(r => setTimeout(r, 50));

    assert.equal(processedList.length, 1);
    assert.equal(processedList[0], dummyMp4);
    assert.equal(processingFiles.has(dummyMp4), false);

    // Restore default processor
    setVideoProcessor(processVideoFile);

    // Cleanup
    fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('queue pauses when Insufficient credits error occurs', async () => {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'subarabify_test_credit_'));
    const movie1 = path.join(tmpDir, 'movie1.mp4');
    const movie2 = path.join(tmpDir, 'movie2.mp4');

    fs.writeFileSync(movie1, 'fake video');
    fs.writeFileSync(movie2, 'fake video');

    const processedList = [];
    setVideoProcessor(async (file) => {
        processedList.push(file);
        if (file === movie1) {
            throw new Error('Insufficient credits');
        }
    });

    fileQueue.length = 0;
    processingFiles.clear();

    enqueueFile(movie1);
    enqueueFile(movie2);

    await new Promise(r => setTimeout(r, 50));

    // Movie1 failed with Insufficient credits, queue should be cleared
    assert.equal(processedList.length, 1);
    assert.equal(processedList[0], movie1);
    assert.equal(fileQueue.length, 0);

    setVideoProcessor(processVideoFile);
    fs.rmSync(tmpDir, { recursive: true, force: true });
});
