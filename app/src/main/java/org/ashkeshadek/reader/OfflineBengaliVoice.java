package org.ashkeshadek.reader;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

final class OfflineBengaliVoice {
    interface Listener {
        void onStatus(String message);
        void onPartial(String text);
        void onResult(String text);
        /**
         * Microphone hears sustained sound but the recognizer still produced no
         * words. Lets the UI tell the reader honestly what the mic hears while
         * sung audio fails to transcribe into matchable lyrics. A null message
         * clears the hint back to the regular listening line.
         */
        void onAudioHint(String message);
        void onError(String message);
        void onFinished();
    }

    private static final int MODEL_RATE = 16000;
    private static final int[] CAPTURE_RATES = {16000, 48000, 44100};
    private static final String MODEL_DIR = "sherpa-onnx-bn-vosk-int8-19d0fc5";
    private static final String HOTWORDS_ASSET = "hotwords.txt";
    // Moderate contextual bias (sherpa's gentle default): strong enough to pull
    // sung fragments toward the book's words, gentle enough not to force wrong
    // songs. Tune toward 1.8 only if transcripts under-detect catalogue words.
    private static final float HOTWORDS_SCORE = 1.5f;
    private static final Object SESSION_LOCK = new Object();

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile AudioRecord recorder;
    private volatile Thread worker;
    private volatile boolean releaseRequested;
    private volatile boolean modelReady;
    private volatile boolean waitingForBegin;
    private volatile boolean stopRequested;
    private volatile boolean sessionCancelled;
    private boolean beginRequested;

    // Microphone-energy sampling for an honest status hint: if the recognizer
    // keeps producing no words while the mic clearly hears sound (e.g. music
    // playback or singing that this speech model cannot decode), the UI says so
    // instead of looking frozen.
    private static final int AUDIO_HINT_WINDOW = 15; // ~1.5 s at 100 ms chunks
    private static final int AUDIO_HINT_MIN_LOUD_CHUNKS = 9;
    private static final int AUDIO_HINT_RMS_FLOOR = 800;
    private static final String AUDIO_HINT_MESSAGE = "শব্দ শুনতে পাচ্ছি — গান মেলানো হচ্ছে…";
    private final int[] energyWindow = new int[AUDIO_HINT_WINDOW];
    private int energyWindowIndex;
    private int energyWindowFilled;
    private boolean audioHintActive;
    private String lastPartialPosted = "";
    private long fedMillis;
    private long decodeNanos;

    OfflineBengaliVoice(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void start() {
        if (isRunning()) return;
        releaseRequested = false;
        modelReady = false;
        waitingForBegin = false;
        stopRequested = false;
        sessionCancelled = false;
        beginRequested = false;
        worker = new Thread(this::runRecognition, "ashke-bengali-asr");
        worker.start();
    }

    synchronized boolean isRunning() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    boolean isModelReady() { return modelReady; }
    boolean isWaitingForBegin() { return waitingForBegin; }
    boolean isReleasing() { return releaseRequested; }

    synchronized void beginListening() {
        if (releaseRequested || !modelReady) return;
        sessionCancelled = false;
        stopRequested = false;
        beginRequested = true;
        notifyAll();
    }

    void finish() {
        stopRequested = true;
        stopRecorder();
        synchronized (this) { notifyAll(); }
    }

    void cancel() {
        if (!waitingForBegin) {
            sessionCancelled = true;
            stopRequested = true;
            stopRecorder();
        }
        synchronized (this) { notifyAll(); }
    }

    void release() {
        releaseRequested = true;
        sessionCancelled = true;
        stopRequested = true;
        stopRecorder();
        synchronized (this) { notifyAll(); }
    }

    void awaitStopped(long timeoutMs) {
        Thread current = worker;
        if (current == null || current == Thread.currentThread()) return;
        try { current.join(timeoutMs); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopRecorder() {
        AudioRecord current = recorder;
        if (current != null) {
            try {
                if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop();
            } catch (IllegalStateException ignored) { }
        }
    }

    private void status(String value) {
        main.post(() -> { if (!sessionCancelled && !releaseRequested) listener.onStatus(value); });
    }

    /**
     * Posts a transcript preview to the UI only when it actually changed. The
     * recognizer produces a result for every ~100 ms chunk; without this guard
     * unchanged text crosses the process boundary dozens of times per second
     * and burns main-thread time that the :voice decoder needs for beam search.
     */
    private void partial(String value) {
        if (value == null || value.equals(lastPartialPosted)) return;
        lastPartialPosted = value;
        main.post(() -> { if (!sessionCancelled && !releaseRequested) listener.onPartial(value); });
    }

    private void error(String value) {
        main.post(() -> { if (!releaseRequested) listener.onError(value); });
    }

    private static final class AudioInput {
        final AudioRecord record;
        final int sampleRate;
        AudioInput(AudioRecord record, int sampleRate) {
            this.record = record;
            this.sampleRate = sampleRate;
        }
    }

    private AudioInput createAudioInput() {
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            for (int rate : CAPTURE_RATES) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) continue;
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate * 2 / 5));
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        return new AudioInput(candidate, rate);
                    }
                } catch (IllegalArgumentException ignored) { }
                if (candidate != null) try { candidate.release(); } catch (Exception ignored) { }
            }
        }
        throw new IllegalStateException("এই ফোনের মাইক্রোফোনে সমর্থিত অডিও রেট পাওয়া যায়নি।");
    }

    private float[] toModelSamples(short[] pcm, int count, int sourceRate) {
        if (sourceRate == MODEL_RATE) {
            float[] out = new float[count];
            for (int i = 0; i < count; i++) out[i] = pcm[i] / 32768.0f;
            return out;
        }
        int size = (int) Math.floor(count * (MODEL_RATE / (double) sourceRate));
        if (size <= 0) return new float[0];
        double ratio = sourceRate / (double) MODEL_RATE;
        // Linear interpolation alone folds every harmonic above 8 kHz back
        // into the speech band, which the model hears as garbage consonants.
        // A short box average before decimation reduces that aliasing on the
        // fallback capture paths (48/44.1 kHz devices).
        int step = (int) Math.round(ratio);
        if (step < 1) step = 1;
        float[] out = new float[size];
        for (int i = 0; i < size; i++) {
            int start = (int) (i * ratio);
            int end = Math.min(start + step, count);
            long sum = 0;
            for (int j = start; j < end; j++) sum += pcm[j];
            out[i] = sum / (float) (end - start) / 32768.0f;
        }
        return out;
    }

    /**
     * Keeps a short rolling window of chunk loudness. When sound is sustained
     * but no words have been recognized yet (or recognition fell back to
     * nothing), the listener hears that the microphone really is receiving the
     * song. Transitions are posted only; steady states never re-send.
     */
    private void trackMicrophoneEnergy(short[] pcm, int count, boolean wordsHeard) {
        long sumOfSquares = 0L;
        for (int i = 0; i < count; i++) {
            long sample = pcm[i];
            sumOfSquares += sample * sample;
        }
        int rms = count <= 0 ? 0 : (int) Math.sqrt(sumOfSquares / count);
        energyWindow[energyWindowIndex] = rms;
        energyWindowIndex = (energyWindowIndex + 1) % AUDIO_HINT_WINDOW;
        if (energyWindowFilled < AUDIO_HINT_WINDOW) energyWindowFilled++;

        int loud = 0;
        for (int value : energyWindow) {
            if (value >= AUDIO_HINT_RMS_FLOOR) loud++;
        }
        boolean soundPresent = energyWindowFilled >= AUDIO_HINT_WINDOW
                && loud >= AUDIO_HINT_MIN_LOUD_CHUNKS;
        boolean shouldHint = soundPresent && !wordsHeard;
        if (shouldHint == audioHintActive) return;
        audioHintActive = shouldHint;
        main.post(() -> {
            if (!sessionCancelled && !releaseRequested) {
                listener.onAudioHint(shouldHint ? AUDIO_HINT_MESSAGE : null);
            }
        });
    }

    /**
     * Every five seconds of captured audio, log how much wall-clock time beam
     * search spent decoding it. A ratio above 1.0 means the phone cannot keep
     * up with real time: the transcript then lags and may only arrive after
     * the reader stopped talking. Collected from real device sessions to
     * decide whether maxActivePaths or the thread count needs per-device
     * tuning instead of guesses.
     */
    private void logDecodeLoad(long fedMs, long decodeNs) {
        double decodeMs = decodeNs / 1000000.0;
        Log.i("AshkeShadekVoice", String.format(java.util.Locale.ROOT,
                "decode-load fedMs=%d decodeMs=%.0f ratio=%.2f",
                fedMs, decodeMs, decodeMs / fedMs));
    }

    private void runRecognition() {
        synchronized (SESSION_LOCK) { runRecognitionLoop(); }
    }

    private void runRecognitionLoop() {
        OnlineRecognizer recognizer = null;
        try {
            status("বাংলা অফলাইন মডেল চালু হচ্ছে…");
            recognizer = createRecognizer();
            modelReady = true;
            waitingForBegin = true;
            status("প্রস্তুত। এখন গানের একটি লাইন বলুন—মিল পাওয়া গেলে গানটি ছুঁয়ে খুলুন।");

            while (!releaseRequested) {
                synchronized (this) {
                    while (!beginRequested && !releaseRequested) wait();
                    if (releaseRequested) break;
                    beginRequested = false;
                    stopRequested = false;
                    sessionCancelled = false;
                    waitingForBegin = false;
                }
                runListeningSession(recognizer);
                if (!releaseRequested) waitingForBegin = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!releaseRequested) error("ভয়েস সার্চ বন্ধ হয়ে গেছে। আবার চেষ্টা করুন।");
        } catch (Throwable failure) {
            Log.e("AshkeShadekVoice", "Offline Bengali model failed", failure);
            if (!releaseRequested) error(messageFor(failure));
        } finally {
            waitingForBegin = false;
            modelReady = false;
            if (recognizer != null) try { recognizer.release(); } catch (Exception ignored) { }
            synchronized (this) {
                worker = null;
                notifyAll();
            }
        }
    }

    private OnlineRecognizer createRecognizer() {
        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(MODEL_RATE);
        feature.setFeatureDim(80);
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(MODEL_DIR + "/encoder.int8.onnx");
        transducer.setDecoder(MODEL_DIR + "/decoder.onnx");
        transducer.setJoiner(MODEL_DIR + "/joiner.int8.onnx");
        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(MODEL_DIR + "/tokens.txt");
        // Beam search with hotword biasing costs more than greedy decoding;
        // two threads keep recognition real-time on octa-core Android 7 phones.
        model.setNumThreads(2);
        model.setDebug(false);
        model.setProvider("cpu");
        model.setModelType("zipformer2");
        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(model);
        config.setEnableEndpoint(true);
        // Contextual biasing toward the songbook: the decoder is guided by the
        // book's titles, first-line phrases and rare words (asset hotwords.txt,
        // generated by tools/generate_hotwords.py). Hotwords only apply with
        // modified_beam_search decoding; a missing file falls back to greedy
        // search so voice search still works, just unbiased.
        String hotwordsFile = stageHotwordsFile();
        if (hotwordsFile.isEmpty()) {
            config.setDecodingMethod("greedy_search");
        } else {
            config.setDecodingMethod("modified_beam_search");
            config.setMaxActivePaths(4);
            config.setHotwordsFile(hotwordsFile);
            config.setHotwordsScore(HOTWORDS_SCORE);
        }
        return new OnlineRecognizer(context.getAssets(), config);
    }

    /**
     * sherpa-onnx reads the hotword list from the filesystem, so stage the
     * bundled asset into cache storage and return its absolute path. An empty
     * return keeps the session alive without biasing instead of failing it.
     */
    private String stageHotwordsFile() {
        java.io.File staged = new java.io.File(context.getCacheDir(), HOTWORDS_ASSET);
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = context.getAssets().open(HOTWORDS_ASSET);
            out = new java.io.FileOutputStream(staged);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return staged.getAbsolutePath();
        } catch (Exception failure) {
            Log.w("AshkeShadekVoice", "Hotword file unavailable; continuing unbiased", failure);
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
    }

    private void runListeningSession(OnlineRecognizer recognizer) {
        OnlineStream stream = null;
        AudioRecord audio = null;
        StringBuilder completedText = new StringBuilder();
        String latestText = "";
        try {
            if (releaseRequested || stopRequested || sessionCancelled) return;
            stream = recognizer.createStream("");
            AudioInput input = createAudioInput();
            audio = input.record;
            recorder = audio;
            if (releaseRequested || stopRequested || sessionCancelled) return;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("মাইক্রোফোন রেকর্ডিং শুরু করতে পারেনি।");
            }
            status("শুনছি — একটি ছোট গানের লাইন বলুন।");
            short[] buffer = new short[Math.max(1600, input.sampleRate / 10)];
            java.util.Arrays.fill(energyWindow, 0);
            energyWindowIndex = 0;
            energyWindowFilled = 0;
            audioHintActive = false;
            lastPartialPosted = "";
            fedMillis = 0;
            decodeNanos = 0;

            while (!stopRequested && !releaseRequested && !sessionCancelled) {
                int count = audio.read(buffer, 0, buffer.length);
                if (count < 0) {
                    if (stopRequested || releaseRequested || sessionCancelled) break;
                    throw new IllegalStateException("মাইক্রোফোন থেকে অডিও পাওয়া যাচ্ছে না (" + count + ")।");
                }
                if (count == 0) continue;
                trackMicrophoneEnergy(buffer, count,
                        completedText.length() > 0 || !latestText.isEmpty());
                float[] samples = toModelSamples(buffer, count, input.sampleRate);
                if (samples.length == 0) continue;
                stream.acceptWaveform(samples, MODEL_RATE);
                long decodeStart = System.nanoTime();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                decodeNanos += System.nanoTime() - decodeStart;
                fedMillis += count * 1000L / input.sampleRate;
                if (fedMillis >= 5000) {
                    logDecodeLoad(fedMillis, decodeNanos);
                    fedMillis = 0;
                    decodeNanos = 0;
                }

                OnlineRecognizerResult result = recognizer.getResult(stream);
                latestText = result == null || result.getText() == null ? "" : result.getText().trim();
                String preview = joinTranscript(completedText.toString(), latestText);
                if (!preview.isEmpty()) partial(preview);

                if (recognizer.isEndpoint(stream)) {
                    OnlineRecognizerResult finalResult = recognizer.getResult(stream);
                    String phrase = finalResult == null || finalResult.getText() == null
                            ? latestText : finalResult.getText().trim();
                    if (!phrase.isEmpty()) {
                        if (completedText.length() > 0) completedText.append(' ');
                        completedText.append(phrase);
                    }
                    latestText = "";
                    recognizer.reset(stream);
                    if (completedText.length() > 0) partial(completedText.toString());
                }
            }

            if (stopRequested && !sessionCancelled && !releaseRequested) {
                stream.inputFinished();
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult finalResult = recognizer.getResult(stream);
                latestText = finalResult == null || finalResult.getText() == null
                        ? "" : finalResult.getText().trim();
                String phrase = joinTranscript(completedText.toString(), latestText);
                if (!phrase.isEmpty()) {
                    String finalPhrase = phrase;
                    main.post(() -> { if (!releaseRequested) listener.onResult(finalPhrase); });
                } else {
                    error("কোনো কথা শনাক্ত হয়নি। আবার বলুন অথবা লিখে গান খুঁজুন।");
                }
            }
        } catch (Throwable failure) {
            Log.e("AshkeShadekVoice", "Offline Bengali recognition failed", failure);
            if (!releaseRequested && !sessionCancelled) error(messageFor(failure));
        } finally {
            recorder = null;
            if (audio != null) {
                try { if (audio.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) audio.stop(); }
                catch (Exception ignored) { }
                try { audio.release(); } catch (Exception ignored) { }
            }
            if (stream != null) try { stream.release(); } catch (Exception ignored) { }
            if (!releaseRequested) main.post(listener::onFinished);
        }
    }

    private static String messageFor(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? "অফলাইন ভয়েস সার্চ চালু করা যায়নি। লিখে গান খুঁজুন।"
                : "ভয়েস সার্চ: " + message;
    }

    private static String joinTranscript(String first, String second) {
        String a = first == null ? "" : first.trim();
        String b = second == null ? "" : second.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }
}
